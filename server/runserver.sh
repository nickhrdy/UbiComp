#!/bin/bash
. venv/bin/activate
gunicorn -b 127.0.0.1:5000 server:app
