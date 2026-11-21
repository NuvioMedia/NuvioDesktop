#!/usr/bin/env python3
"""Own a mock ScreenSaver service on a PRIVATE bus while running the given tests.

python -m pip install dbus-next==0.2.3
dbus-run-session -- python3 scripts/test-linux-screensaver-inhibit.py -- \
    ./gradlew :composeApp:desktopTest --no-daemon --rerun-tasks \
    --tests '*LinuxKeepAwakeControllerTest' --tests '*LinuxScreenSaverDbusMarshallingTest' \
    --tests '*LinuxScreenSaverConnectionTest' \
    --tests '*LinuxScreenSaverInhibitorDbusTest'
Never run this mock against the desktop's real session bus.
"""

import asyncio
import os
import sys

from dbus_next import Message, MessageType
from dbus_next.aio import MessageBus

SERVICE = "org.freedesktop.ScreenSaver"
PATH = "/org/freedesktop/ScreenSaver"


async def main():
    command = sys.argv[1:]
    if command[:1] == ["--"]:
        command = command[1:]
    if not command:
        sys.exit("Provide the test command after --")
    bus = await MessageBus().connect()
    # Refuse to replace or compete with any real screensaver implementation.
    reply = await bus.call(Message(
        destination="org.freedesktop.DBus", path="/org/freedesktop/DBus",
        interface="org.freedesktop.DBus", member="NameHasOwner",
        signature="s", body=[SERVICE],
    ))
    if reply.body[0]:
        sys.exit("ScreenSaver already exists: use a fresh dbus-run-session")
    await bus.request_name(SERVICE)
    await bus.call(Message(
        destination="org.freedesktop.DBus", path="/org/freedesktop/DBus",
        interface="org.freedesktop.DBus", member="AddMatch", signature="s",
        body=["type='signal',interface='org.freedesktop.DBus',member='NameOwnerChanged'"],
    ))
    active = {}
    senders = set()
    counts = dict(acquisitions=0, uninhibits=0, disconnects=0, wrongSender=0, autoRemoved=0)
    cookie = 42
    mode = "normal"

    async def delayed_reply(message):
        await asyncio.sleep(0.3)
        await bus.send(Message.new_method_return(message, signature="u", body=[cookie]))

    def handle(message):
        nonlocal cookie, mode
        if message.message_type == MessageType.SIGNAL and message.member == "NameOwnerChanged":
            name, old, new = message.body
            if not new and name in senders:
                senders.remove(name)
                counts["disconnects"] += 1
                for token, owner in list(active.items()):
                    if owner == name:
                        del active[token]
                        counts["autoRemoved"] += 1
            return False
        if message.message_type != MessageType.METHOD_CALL or message.path != PATH:
            return False
        if message.interface == "com.nuvio.Test":
            if message.member == "Reset":
                if active:
                    return Message.new_error(message, "com.nuvio.Test.LeakedInhibition", str(active))
                cookie, mode = message.body
                senders.clear()
                for key in counts:
                    counts[key] = 0
                return Message.new_method_return(message)
            if message.member == "GetState":
                fields = dict(active=len(active), **counts)
                return Message.new_method_return(
                    message, signature="s", body=[";".join(f"{key}={value}" for key, value in fields.items())],
                )
        if message.interface == SERVICE:
            if message.member == "Inhibit":
                if message.signature != "ss" or message.body != ["Nuvio", "Media playback"]:
                    return Message.new_error(message, "org.freedesktop.DBus.Error.InvalidArgs", "expected ss")
                if active:
                    return Message.new_error(message, "com.nuvio.Test.DuplicateInhibit", str(active))
                senders.add(message.sender)
                active[cookie] = message.sender
                counts["acquisitions"] += 1
                if mode == "timeout":
                    return True  # Intentionally no reply: acquisition must time out and disconnect.
                if mode == "delay":
                    asyncio.create_task(delayed_reply(message))
                    return True
                if mode == "bad-cookie":
                    return Message.new_method_return(message, signature="s", body=["invalid"])
                return Message.new_method_return(message, signature="u", body=[cookie])
            if message.member == "UnInhibit":
                counts["uninhibits"] += 1
                token = message.body[0]
                if message.signature != "u" or active.get(token) != message.sender:
                    counts["wrongSender"] += 1
                    return Message.new_error(message, "org.freedesktop.DBus.Error.AccessDenied", "wrong cookie owner")
                if mode == "fail-release":
                    return Message.new_error(message, "org.freedesktop.DBus.Error.Failed", "simulated failure")
                if mode == "timeout-release":
                    return True  # Disconnect must release the cookie after UnInhibit times out.
                del active[token]
                return Message.new_method_return(message)
        return False

    bus.add_message_handler(handle)
    env = dict(os.environ, NUVIO_INHIBITOR_DBUS_TEST="1")
    print("Mock ScreenSaver ready on private bus; running lifecycle and D-Bus tests", flush=True)
    process = await asyncio.create_subprocess_exec(*command, env=env)
    result = await process.wait()
    await asyncio.sleep(0.05)
    if active:
        print(f"FAIL: leaked inhibition(s): {active}", file=sys.stderr)
        result = 1
    bus.disconnect()
    return result


if __name__ == "__main__":
    sys.exit(asyncio.run(main()))
