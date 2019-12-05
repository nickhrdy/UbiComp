# UbiComp
CS5204: Operating Systems Semester Project



To install python dependencies
```
pip install -r requirements.txt
```

## Deploying the Flask Server
* Make sure a python virtual environment has been created in `server/` and the python dependencies have been installed
* copy `ubicomp.service` to `/etc/systemd/system`
	> The service can be controlled with `systemctl [start|stop|restart] ubicomp.service
