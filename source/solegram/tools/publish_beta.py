#!/usr/bin/env python3
"""Publishes a sealed Solegram beta into the distribution repository (see updater/BetaChannel.java).

    publish_beta.py <dist repo> <version> <build> <apk> <changelog file>
    publish_beta.py <dist repo> --init        (opens the channel: beta/update.json with no build yet)

The access code is SOLEGRAM_BETA_CODE in local.properties (next to this repository's settings.gradle, not in git).
The APK is encrypted with AES-256-CTR under a key PBKDF2-HMAC-SHA256 makes from the code, with an HMAC-SHA256
over the iv and the encrypted bytes; the changelog is sealed the same way. Nothing in beta/ is of use without it.
"""
import base64
import hashlib
import hmac
import json
import os
import secrets
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ITER = 120000
CHECK_TEXT = b"solegram-beta-check"


def code():
    with open(os.path.join(ROOT, "local.properties")) as f:
        for line in f:
            k, _, v = line.partition("=")
            if k.strip() == "SOLEGRAM_BETA_CODE" and v.strip():
                return "".join(v.split()).replace("-", "").upper()
    sys.exit("SOLEGRAM_BETA_CODE is not set in local.properties")


def keys(c, salt):
    k = hashlib.pbkdf2_hmac("sha256", c.encode(), salt, ITER, 64)
    return k[:32], k[32:]


def ctr(key, iv, data_in, data_out):
    subprocess.run(["openssl", "enc", "-aes-256-ctr", "-nosalt", "-K", key.hex(), "-iv", iv.hex(),
                    "-in", data_in, "-out", data_out], check=True)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    repo = sys.argv[1]
    c = code()
    salt = secrets.token_bytes(16)
    enc, mac = keys(c, salt)
    meta = {"salt": salt.hex(), "iter": ITER, "check": hmac.new(mac, CHECK_TEXT, "sha256").hexdigest()}
    os.makedirs(os.path.join(repo, "beta"), exist_ok=True)
    if sys.argv[2] == "--init":
        meta["build"] = 0
    else:
        version, build, apk, changelog = sys.argv[2], int(sys.argv[3]), sys.argv[4], sys.argv[5]
        name = "beta/Solegram-%s-b%d.sealed" % (version, build)
        out = os.path.join(repo, name)
        iv = secrets.token_bytes(16)
        ctr(enc, iv, apk, out)
        m = hmac.new(mac, iv, "sha256")
        with open(out, "rb") as f:
            for chunk in iter(lambda: f.read(1 << 20), b""):
                m.update(chunk)
        text = open(changelog, encoding="utf-8").read().strip().encode()
        civ = secrets.token_bytes(16)
        tmp_in, tmp_out = out + ".txt", out + ".txt.enc"
        with open(tmp_in, "wb") as f:
            f.write(text)
        ctr(enc, civ, tmp_in, tmp_out)
        sealed_text = open(tmp_out, "rb").read()
        os.remove(tmp_in)
        os.remove(tmp_out)
        meta.update({
            "version": version, "build": build, "apk": name,
            "size": os.path.getsize(apk), "sha256": sha256(apk),
            "sealedSize": os.path.getsize(out), "sealedSha256": sha256(out),
            "iv": iv.hex(), "mac": m.hexdigest(),
            "changelog": {"iv": civ.hex(), "data": base64.b64encode(sealed_text).decode(),
                          "mac": hmac.new(mac, civ + sealed_text, "sha256").hexdigest()},
        })
    with open(os.path.join(repo, "beta", "update.json"), "w") as f:
        json.dump(meta, f, ensure_ascii=False, indent=2)
        f.write("\n")
    print("beta/update.json:", meta.get("version", "channel opened"), meta.get("build"))


if __name__ == "__main__":
    main()
