"""Runs the existing ProxyServer on a background thread with its own event loop."""
import asyncio
import logging
import socket
import threading
import time

log = logging.getLogger("Engine")


class EngineError(RuntimeError):
    pass


def port_open(host: str, port: int) -> bool:
    try:
        with socket.create_connection((host, port), timeout=0.5):
            return True
    except OSError:
        return False


class Engine:
    def __init__(self, config: dict):
        self.config = config
        self.loop: asyncio.AbstractEventLoop | None = None
        self.thread: threading.Thread | None = None
        self.task: asyncio.Task | None = None
        self.error: BaseException | None = None

    @property
    def running(self) -> bool:
        return bool(self.thread and self.thread.is_alive())

    def start(self, timeout: float = 45.0) -> None:
        host, port = self.config["listen_host"], self.config["http_port"]
        if port_open(host, port):
            raise EngineError(f"پورت {port} روی سیستم شما در حال استفاده است.")
        self.error = None
        self.thread = threading.Thread(target=self._main, name="proxy-engine", daemon=True)
        self.thread.start()
        deadline = time.time() + timeout
        while time.time() < deadline:
            if not self.thread.is_alive():
                raise EngineError(f"راه‌اندازی ناموفق بود: {self.error}")
            if port_open(host, port):
                return
            time.sleep(0.1)
        self.stop()
        raise EngineError("زمان راه‌اندازی تمام شد.")

    def _main(self) -> None:
        from proxy.proxy_server import ProxyServer  # imported late: needs bootstrap
        self.loop = asyncio.new_event_loop()
        asyncio.set_event_loop(self.loop)
        server = None
        try:
            server = ProxyServer(self.config)
            self.task = self.loop.create_task(server.start())
            self.loop.run_until_complete(self.task)
        except asyncio.CancelledError:
            pass
        except BaseException as exc:  # noqa: BLE001
            self.error = exc
            log.exception("engine crashed")
        finally:
            try:
                if server:
                    self.loop.run_until_complete(server.stop())
                stray = [t for t in asyncio.all_tasks(self.loop) if not t.done()]
                for t in stray:
                    t.cancel()
                if stray:
                    self.loop.run_until_complete(asyncio.gather(*stray, return_exceptions=True))
            except Exception:  # noqa: BLE001
                pass
            self.loop.close()

    def stop(self) -> None:
        if self.loop and self.task and not self.task.done():
            self.loop.call_soon_threadsafe(self.task.cancel)
        if self.thread:
            self.thread.join(timeout=10)
        self.thread = None
