"""Run the real Windows executable against disposable files and a loopback peer."""
import pathlib
import socket
import struct
import subprocess
import tempfile
import threading

EXE = pathlib.Path(__file__).with_name("accessible-browser.exe")


def field(peer):
    def read(n):
        data = bytearray()
        while len(data) < n:
            chunk = peer.recv(n - len(data))
            if not chunk:
                raise EOFError()
            data.extend(chunk)
        return bytes(data)
    size = struct.unpack(">I", read(4))[0]
    assert size <= 131072
    return read(size).decode("utf-8")


def roundtrip(reply, expected):
    errors = []
    with socket.socket() as server, tempfile.TemporaryDirectory(prefix="browser-wire-") as temp:
        server.bind(("127.0.0.1", 0))
        server.listen(1)
        server.settimeout(10)
        config = pathlib.Path(temp) / "session.ini"
        config.write_text(f"[session]\nport={server.getsockname()[1]}\ntoken=test-session\n", encoding="utf-8")

        def receive():
            try:
                with server.accept()[0] as peer:
                    peer.settimeout(5)
                    assert peer.recv(4) == b"WFB1"
                    assert field(peer) == "test-session"
                    request_id = field(peer)
                    assert field(peer) == "launch"
                    assert field(peer) == "D:\\日本 game\\spaced game.exe"
                    def write(value):
                        data = value.encode("utf-8")
                        peer.sendall(struct.pack(">I", len(data)) + data)
                    write(request_id)
                    peer.sendall(struct.pack(">I", 0))
                    peer.sendall(struct.pack(">I", len(reply)) + reply)
            except Exception as error:
                errors.append(error)

        thread = threading.Thread(target=receive)
        thread.start()
        result = subprocess.run([str(EXE), "--bridge-test", str(config), "D:\\日本 game\\spaced game.exe"], timeout=15)
        thread.join(10)
        assert not thread.is_alive()
        assert not errors, errors
        assert result.returncode == expected, result.returncode


if __name__ == "__main__":
    result = subprocess.run([str(EXE), "--self-test"], timeout=30)
    assert result.returncode == 0, f"Native filesystem/control scenario failed: {result.returncode}"
    roundtrip("Launch ready 日本".encode("utf-8"), 0)
    roundtrip(b"\xc0\xaf", 1236)  # Invalid UTF-8 is not accepted as a successful response.
    print("PASS: native filesystem/control scenarios and Unicode/malformed loopback protocol")
