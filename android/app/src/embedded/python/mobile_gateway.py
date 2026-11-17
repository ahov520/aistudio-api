"""Android adapter. Existing upstream implementation is unchanged."""
import os
from pathlib import Path
import threading

_server = None
_stop_requested = threading.Event()
_lock = threading.Lock()

def run(data_dir, template_path, api_key, admin_password):
    global _server
    import yaml
    import uvicorn
    directory = Path(data_dir)
    config = directory / "config.yaml"
    if config.exists():
        settings = yaml.safe_load(config.read_text()) or {}
    else:
        settings = yaml.safe_load(Path(template_path).read_text()) or {}
    settings["api_keys"] = [api_key]
    settings.setdefault("logging", {}).update(dump_requests=False, dump_to_file=False)
    config.write_text(yaml.safe_dump(settings, allow_unicode=True), encoding="utf-8")
    os.environ.update(AISTUDIO_DATA_DIR=str(directory / "data"),
                      AISTUDIO_CONFIG_FILE=str(config),
                      AISTUDIO_WEB_PASSWORD=admin_password,
                      AISTUDIO_PORT="8080",
                      AISTUDIO_DUMP_REQUESTS="0", AISTUDIO_DUMP_FILE="0")
    from aistudio_api.api.app import app
    class ThreadServer(uvicorn.Server):
        def install_signal_handlers(self):
            pass
    server = ThreadServer(uvicorn.Config(app, host="127.0.0.1", port=8080, access_log=False, log_config=None))
    with _lock:
        _server = server
        if _stop_requested.is_set():
            _server = None
            _stop_requested.clear()
            return
    try:
        server.run()
    finally:
        with _lock:
            _server = None
            _stop_requested.clear()

def stop():
    with _lock:
        _stop_requested.set()
        if _server is not None:
            _server.should_exit = True
