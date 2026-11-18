# Plugin guard self-test

Runs attack "plugins" against the real `solegram_guard.py` off-device, with a fake `java`
bridge, to check that a plugin granted nothing cannot read the account session, the chat
database, the secrets, or escape attribution. Each `attacks/NN_*.py` defines `run(ctx)`.

Copy the current guard in and run every attack:

    cp ../../../TMessagesProj_App/src/main/python/solegram_guard.py .
    for a in attacks/*.py; do echo -n "$a "; python3 -I harness.py "$a"; done

Expected: every `attacks/[0-9]*` prints `BLOCKED ...`; every `attacks/9*` (benign) prints `PASS`.
A `LEAK` line is a hole. Not a device test — it exercises the Python layer (audit hook, path
checks, jclass/import name checks, thread attribution, guard-control protection), not Chaquopy's
own class resolution.
