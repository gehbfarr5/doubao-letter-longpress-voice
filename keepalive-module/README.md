This KernelSU/SukiSU module keeps `DoubaoVoiceSendA11yService` present in secure accessibility settings after boot and later ColorOS cleanup.
It runs as a root `late_start service`, so it does not depend on the app process, broadcasts, or prior `su` grants.
Every repair writes a timestamped line to `/data/local/tmp/doubaovoicesend_keepalive.log`, with automatic truncation above 64 KB.

Install:
```sh
cd keepalive-module
zip -j /data/local/tmp/doubaovoicesend_keepalive.zip module.prop service.sh
ksud module install /data/local/tmp/doubaovoicesend_keepalive.zip
reboot
```

Verify:
```sh
settings get secure enabled_accessibility_services
settings get secure accessibility_enabled
tail -n 20 /data/local/tmp/doubaovoicesend_keepalive.log
```
