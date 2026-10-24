import { createInterface } from "node:readline";
import { resolve } from "node:path";
import { AdminService, AdminSetupError } from "./admin/service.js";
import { PasswordHasher } from "./auth/passwords.js";
import { AuthService } from "./auth/service.js";
import { generateTotpSecret, otpauthUri, base32Encode, verifyTotp } from "./auth/totp.js";
import { ConfigError, loadConfig } from "./config/env.js";
import { PrismaDatabase } from "./db/database.js";
import { AppError } from "./errors.js";

/*
 * Server-side operations that must not be reachable over the network (docs/04 §7):
 *   admin:create --username <name>        first-time administrator setup (password + TOTP enrolment)
 *   admin:reset  --username <name>        administrator recovery (new password + new TOTP secret)
 *   user:create  --username <name>        create one of the two app users (prints a temporary password)
 *   user:reset-password --username <name> emergency reset when the Admin Console is unreachable
 *   user:list                             list app users (no secrets)
 * Passwords are read from the terminal without echo, or line by line from stdin when piped.
 */

const out = (s = "") => process.stdout.write(s + "\n");

class LineReader {
  private readonly lines: string[] = [];
  private readonly waiters: ((line: string | null) => void)[] = [];
  private closed = false;
  private rl: ReturnType<typeof createInterface> | undefined;

  /** Opened only when a command actually prompts, so non-interactive commands exit promptly. */
  private open() {
    if (this.rl) return;
    this.rl = createInterface({ input: process.stdin, terminal: false });
    this.rl.on("line", (line) => this.push(line));
    this.rl.on("close", () => {
      this.closed = true;
      while (this.waiters.length) this.waiters.shift()!(null);
    });
  }

  close() {
    if (!this.rl) return;
    this.rl.close();
    process.stdin.destroy();
  }

  private push(line: string) {
    const waiter = this.waiters.shift();
    if (waiter) waiter(line);
    else this.lines.push(line);
  }

  async ask(prompt: string, secret: boolean): Promise<string> {
    if (process.stdin.isTTY) return askTty(prompt, secret);
    this.open();
    process.stderr.write(prompt);
    const line = this.lines.shift() ?? (this.closed ? null : await new Promise<string | null>((r) => this.waiters.push(r)));
    process.stderr.write("\n");
    if (line === null) throw new Error("unexpected end of input");
    return line;
  }
}

function askTty(prompt: string, secret: boolean): Promise<string> {
  return new Promise((resolvePrompt) => {
    const rl = createInterface({ input: process.stdin, output: process.stdout, terminal: true });
    const output = rl as unknown as { _writeToOutput: (s: string) => void; output: NodeJS.WriteStream };
    if (secret) {
      output._writeToOutput = (s: string) => {
        if (s.includes(prompt)) output.output.write(prompt);
      };
    }
    rl.question(prompt, (answer) => {
      rl.close();
      if (secret) process.stdout.write("\n");
      resolvePrompt(answer);
    });
  });
}

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(`--${name}`);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

async function askNewPassword(reader: LineReader, label: string): Promise<string> {
  const first = await reader.ask(`${label}: `, true);
  const second = await reader.ask("Repeat it: ", true);
  if (first !== second) throw new AdminSetupError("The passwords do not match.");
  return first;
}

async function enrolTotp(reader: LineReader, username: string): Promise<Buffer> {
  const secret = generateTotpSecret();
  out("\nAdd this account to your authenticator app (shown once):");
  out(`  Secret: ${base32Encode(secret)}`);
  out(`  URI:    ${otpauthUri(secret, username)}`);
  const code = await reader.ask("Enter the 6-digit code from the app to confirm: ", false);
  if (verifyTotp(secret, code.trim(), Date.now(), null) === null) throw new AdminSetupError("That code is not valid. Nothing was saved.");
  return secret;
}

async function main() {
  const command = process.argv[2];
  let config;
  try {
    config = loadConfig();
  } catch (err) {
    if (err instanceof ConfigError) {
      console.error(err.message);
      process.exit(1);
    }
    throw err;
  }
  const database = new PrismaDatabase(config, resolve(config.MIGRATIONS_DIR));
  const hasher = new PasswordHasher({ memoryCost: config.ARGON2_MEMORY_KIB, timeCost: config.ARGON2_TIME_COST });
  const auth = new AuthService(database.prisma, hasher);
  const admin = new AdminService(database.prisma, hasher, config.ADMIN_TOTP_ENC_KEY);
  const reader = new LineReader();
  const system = { type: "system" as const, id: null };

  try {
    switch (command) {
      case "admin:create":
      case "admin:reset": {
        const username = arg("username");
        if (!username) throw new AdminSetupError("--username is required");
        if (command === "admin:create" && (await admin.isInitialised())) throw new AdminSetupError("An administrator already exists. Use admin:reset.");
        const password = await askNewPassword(reader, "New admin password (min 16 characters)");
        admin.validateCredentials(username, password);
        const secret = await enrolTotp(reader, username);
        if (command === "admin:create") await admin.createAdmin(username, password, secret);
        else await admin.resetAdmin(username, password, secret);
        out(`\nAdministrator ${command === "admin:create" ? "created" : "reset"}. All earlier admin sessions are ended.`);
        break;
      }
      case "user:create": {
        const username = arg("username");
        if (!username) throw new AdminSetupError("--username is required");
        const { user, temporaryPassword } = await auth.createUser(username, system);
        out(`Created user ${user.username} (slot ${user.slot}).`);
        out(`Temporary password (shown once): ${temporaryPassword}`);
        out("The user must change it at first login.");
        break;
      }
      case "user:reset-password": {
        const username = arg("username");
        const user = username ? await database.prisma.user.findUnique({ where: { username: username.toLowerCase() } }) : null;
        if (!user) throw new AdminSetupError("No such user.");
        const temporaryPassword = await auth.resetPassword(user.id, system);
        out(`Password reset for ${user.username}; all their sessions are ended.`);
        out(`Temporary password (shown once): ${temporaryPassword}`);
        break;
      }
      case "user:list": {
        const users = await database.prisma.user.findMany({ orderBy: { slot: "asc" } });
        if (users.length === 0) out("No users.");
        for (const u of users) out(`${u.slot}  ${u.username}  ${u.status}${u.mustChangePassword ? "  (must change password)" : ""}`);
        break;
      }
      default:
        out("Commands: admin:create | admin:reset | user:create | user:reset-password (all need --username <name>) | user:list");
        process.exitCode = 1;
    }
  } catch (err) {
    if (err instanceof AdminSetupError || err instanceof AppError) {
      console.error(`Error: ${err.message}`);
      process.exitCode = 1;
    } else {
      throw err;
    }
  } finally {
    reader.close();
    await database.close();
  }
}

main().catch((err: unknown) => {
  console.error("fatal:", err instanceof Error ? err.message : err);
  process.exit(1);
});
