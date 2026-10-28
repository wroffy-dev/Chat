import { createHash } from "node:crypto";
import { createReadStream, createWriteStream, type ReadStream } from "node:fs";
import { readFile, rename, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { Transform, type Readable } from "node:stream";
import { pipeline } from "node:stream/promises";
import { AppError } from "../errors.js";

export interface PublishedApk {
  versionCode: number;
  sha256: string;
  size: number;
}

/** Largest APK accepted; the current debug build is about 40 MB. */
export const MAX_APK_BYTES = 150 * 1024 * 1024;

/**
 * The latest app build, kept as two files in one directory: `latest.apk` and `latest.json`
 * ({versionCode, sha256, size}). Uploads land in a temporary file and replace both only once
 * complete, so a download never sees half a file.
 */
export class AppUpdateStore {
  constructor(private readonly dir: string) {}

  async latest(): Promise<PublishedApk | null> {
    try {
      const meta = JSON.parse(await readFile(join(this.dir, "latest.json"), "utf8")) as PublishedApk;
      return Number.isInteger(meta.versionCode) && /^[0-9a-f]{64}$/.test(meta.sha256) && Number.isInteger(meta.size) ? meta : null;
    } catch {
      return null;
    }
  }

  /** Stores [body] as the latest build. Refuses a version that is not newer than the current one. */
  async publish(body: Readable, versionCode: number): Promise<PublishedApk> {
    const current = await this.latest();
    if (current && versionCode <= current.versionCode) {
      body.resume();
      throw new AppError(409, "CONFLICT", `Version ${current.versionCode} is already published; upload a newer one.`);
    }
    const tmp = join(this.dir, `upload-${process.pid}-${Date.now()}.tmp`);
    const hash = createHash("sha256");
    let size = 0;
    let head = Buffer.alloc(0);
    const counter = new Transform({
      transform(chunk: Buffer, _enc, cb) {
        size += chunk.length;
        if (size > MAX_APK_BYTES) return cb(new AppError(413, "PAYLOAD_TOO_LARGE", "The APK is too large."));
        if (head.length < 4) head = Buffer.concat([head, chunk.subarray(0, 4 - head.length)]);
        hash.update(chunk);
        cb(null, chunk);
      },
    });
    try {
      await pipeline(body, counter, createWriteStream(tmp, { mode: 0o600 }));
      // An APK is a zip file: "PK\x03\x04".
      if (size < 1024 || head.toString("latin1") !== "PK\u0003\u0004") {
        throw new AppError(400, "VALIDATION_FAILED", "That is not an APK file.");
      }
      const meta: PublishedApk = { versionCode, sha256: hash.digest("hex"), size };
      await rename(tmp, join(this.dir, "latest.apk"));
      await writeFile(join(this.dir, "latest.json.tmp"), JSON.stringify(meta), { mode: 0o600 });
      await rename(join(this.dir, "latest.json.tmp"), join(this.dir, "latest.json"));
      return meta;
    } finally {
      await rm(tmp, { force: true });
    }
  }

  /** The latest APK as a stream with its metadata, or null when none is published. */
  async open(): Promise<{ meta: PublishedApk; stream: ReadStream } | null> {
    const meta = await this.latest();
    if (!meta) return null;
    try {
      const stream = createReadStream(join(this.dir, "latest.apk"));
      await new Promise<void>((resolve, reject) => stream.once("open", () => resolve()).once("error", reject));
      return { meta, stream };
    } catch {
      return null;
    }
  }
}
