-- Walkie-talkie (owner decision D-26, 2026-11-12): a third kind of call, "talk": live one-way audio
-- of at most 10 s, never recorded or stored. Only the allowed values of calls.kind change.
ALTER TABLE "calls" DROP CONSTRAINT "calls_kind_check";
ALTER TABLE "calls" ADD CONSTRAINT "calls_kind_check" CHECK ("kind" IN ('video', 'voice', 'talk'));
