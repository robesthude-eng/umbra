#!/usr/bin/env python3
"""Проверка уже запущенного локального сервера настоящими curl-запросами."""

import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--limit", type=int, default=1024,
                        help="должен совпадать с MAX_MEDIA_BYTES запущенного сервера")
    parser.add_argument("--trace", type=Path, help="записать выполненные curl-команды без токенов")
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[1]
    trace = []
    try:
        with tempfile.TemporaryDirectory(prefix="umbra-media-") as tmp:
            data = Path(tmp)
            fixture = data / "mediafixture"
            subprocess.run(["go", "build", "-o", str(fixture), "./scripts/mediafixture"], cwd=project, check=True)
            subprocess.run([str(fixture), "prepare", tmp, str(args.limit)], check=True)

            def curl(label, method, path, expected, role=None, body=None,
                     file=None, mime=None, output=None, chunked=False):
                response = data / (output or label + ".json")
                headers = data / (label + ".headers")
                cmd = ["curl", "--silent", "--show-error", "--max-time", "20",
                       "--dump-header", str(headers), "--output", str(response),
                       "--write-out", "%{http_code}", "--request", method]
                if role:
                    # Токен не попадает в командную строку, трассу или отчёт.
                    cmd += ["--header", "@" + str(data / (role + ".auth"))]
                if body:
                    cmd += ["--header", "Content-Type: application/json", "--data-binary", "@" + str(data / body)]
                if file:
                    cmd += ["--form", "file=@" + str(data / file) + ";type=application/octet-stream;filename=blob.bin"]
                if mime:
                    cmd += ["--form-string", "content_type=" + mime]
                if chunked:
                    cmd += ["--header", "Transfer-Encoding: chunked"]
                cmd += [args.base_url.rstrip("/") + path]
                trace.append(shlex.join(cmd))
                result = subprocess.run(cmd, capture_output=True, text=True, check=True)
                status = int(result.stdout)
                if status != expected:
                    raise RuntimeError(f"{label}: HTTP {status}, expected {expected}; {response.read_text(errors='replace')}")
                print(f"{label}: HTTP {status}", flush=True)
                return response, headers

            curl("health", "GET", "/healthz", 200)
            for role in ("alice", "bob"):
                curl(role + "-register", "POST", "/v1/register", 201, body=role + "-register.json", output=role + "-registered.json")
                curl(role + "-challenge", "POST", "/v1/auth/challenge", 200, body=role + "-challenge-request.json")
                subprocess.run([str(fixture), "sign", tmp, role], check=True)
                response, _ = curl(role + "-verify", "POST", "/v1/auth/verify", 200, body=role + "-verify.json", output=role + "-verified.json")
                token = json.loads(response.read_text())["token"]
                auth = data / (role + ".auth")
                auth.write_text("Authorization: Bearer " + token + "\n")
                auth.chmod(0o600)

            bob_name = json.loads((data / "bob-register.json").read_text())["username"]
            curl("bob-prekeys", "GET", "/v1/users/" + bob_name + "/prekeys", 200)
            response, _ = curl("upload", "POST", "/v1/media", 201, role="alice", file="encrypted.bin")
            metadata = json.loads(response.read_text())
            original = (data / "encrypted.bin").read_bytes()
            assert metadata["size"] == len(original), metadata
            assert metadata["content_type"] == "application/octet-stream", metadata
            assert set(metadata) == {"id", "content_type", "size"}, metadata
            print("upload-response: " + json.dumps(metadata, sort_keys=True), flush=True)
            response, headers = curl("download-as-recipient", "GET", "/v1/media/" + metadata["id"], 200,
                                     role="bob", output="download.bin")
            assert response.read_bytes() == original, "downloaded ciphertext differs"
            header_text = headers.read_text().lower()
            assert "content-type: application/octet-stream" in header_text, header_text
            assert "content-length: " + str(len(original)) in header_text, header_text
            print("ciphertext byte comparison: OK", flush=True)
            print("ciphertext SHA-256: " + hashlib.sha256(original).hexdigest(), flush=True)
            print("download headers: Content-Type=application/octet-stream, Content-Length=" + str(len(original)), flush=True)
            subprocess.run([str(fixture), "verify", tmp], check=True)

            response, _ = curl("exact-limit-upload", "POST", "/v1/media", 201, role="alice", file="exact-limit.bin", mime="audio/ogg")
            exact = json.loads(response.read_text())
            assert exact["size"] == args.limit and exact["content_type"] == "audio/ogg", exact
            response, headers = curl("exact-limit-download", "GET", "/v1/media/" + exact["id"], 200, role="bob", output="exact-download.bin")
            assert response.read_bytes() == (data / "exact-limit.bin").read_bytes()
            assert "content-type: audio/ogg" in headers.read_text().lower()
            curl("oversized", "POST", "/v1/media", 413, role="alice", file="oversized.bin")
            curl("chunked-oversized", "POST", "/v1/media", 413, role="alice", file="oversized.bin", chunked=True)
            curl("missing-id", "GET", "/v1/media/missing_media_id", 404, role="bob")
            curl("upload-without-token", "POST", "/v1/media", 401, file="encrypted.bin")
            curl("download-without-token", "GET", "/v1/media/" + metadata["id"], 401)
            print("ALL CURL SMOKE CHECKS PASSED", flush=True)
    finally:
        if args.trace:
            args.trace.write_text("\n".join(trace) + "\n")


if __name__ == "__main__":
    main()
