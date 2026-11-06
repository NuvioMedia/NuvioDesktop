#!/usr/bin/env python3
"""Exercise dual subtitle commands against an installed mpv using generated local media."""

import json
import shutil
import subprocess
import tempfile
from pathlib import Path


def main():
    for command in ("ffmpeg", "mpv"):
        if shutil.which(command) is None:
            raise SystemExit(f"Install {command} to run native dual subtitle tests.")

    with tempfile.TemporaryDirectory(prefix="nuvio-dual-subtitles-") as directory:
        root = Path(directory)
        subtitles = []
        for name in ("en", "en-forced", "fr", "fr-forced", "external-primary", "external-secondary"):
            path = root / f"{name}.srt"
            path.write_text(f"1\n00:00:00,000 --> 00:00:09,000\n{name}\n", encoding="utf-8")
            subtitles.append(path)
        media = root / "dual.mkv"
        args = ["ffmpeg", "-v", "error", "-f", "lavfi", "-i", "color=c=black:s=640x360:r=1:d=10"]
        for path in subtitles[:4]:
            args += ["-i", str(path)]
        args += ["-map", "0:v"]
        for index in range(4):
            args += ["-map", f"{index + 1}:s", f"-metadata:s:s:{index}",
                     f"language={'eng' if index < 2 else 'fra'}",
                     f"-disposition:s:{index}", "forced" if index % 2 else "0"]
        args += ["-c:v", "ffv1", "-c:s", "srt", str(media)]
        subprocess.run(args, check=True, timeout=30)

        script = root / "dual_subtitles.lua"
        script.write_text("local primary_file = " + json.dumps(str(subtitles[4])) + "\n"
                          + "local secondary_file = " + json.dumps(str(subtitles[5])) + "\n" + '''
local function expect(name, actual, expected)
    if actual ~= expected then error(name .. ": expected " .. tostring(expected) .. ", got " .. tostring(actual)) end
end
local function select_pair(primary, secondary)
    mp.set_property("sid", tostring(primary))
    mp.set_property("secondary-sid", tostring(secondary))
    mp.set_property("sub-visibility", "yes")
    mp.set_property("secondary-sub-visibility", "yes")
end
mp.register_event("file-loaded", function()
    local ok, failure = pcall(function()
        select_pair(1, 3)
        expect("primary language", mp.get_property_number("sid"), 1)
        expect("secondary language", mp.get_property_number("secondary-sid"), 3)
        local selected = 0
        for _, track in ipairs(mp.get_property_native("track-list")) do
            if track.type == "sub" and track.selected then
                selected = selected + 1
                expect("slot selection order", track["main-selection"], track.id == 1 and 0 or 1)
            end
        end
        expect("both tracks selected", selected, 2)
        mp.set_property("secondary-sub-visibility", "no")
        select_pair(1, 3)
        expect("selecting secondary restores visibility", mp.get_property("secondary-sub-visibility"), "yes")
        expect("restoring visibility preserves primary", mp.get_property_number("sid"), 1)
        select_pair(2, 3)
        expect("forced primary", mp.get_property_number("sid"), 2)
        select_pair(1, 4)
        expect("forced secondary", mp.get_property_number("secondary-sid"), 4)
        mp.commandv("sub-add", secondary_file, "auto")
        select_pair(1, 5)
        expect("external secondary preserves primary", mp.get_property_number("sid"), 1)
        mp.commandv("sub-add", primary_file, "auto")
        select_pair(6, 5)
        expect("two external subtitles", mp.get_property_number("secondary-sid"), 5)
        mp.set_property("sid", "1")
        mp.commandv("sub-remove", "6")
        expect("removing primary preserves secondary", mp.get_property_number("secondary-sid"), 5)
        mp.set_property("secondary-sid", "no")
        mp.commandv("sub-remove", "5")
        expect("disabling secondary preserves primary", mp.get_property_number("sid"), 1)
        mp.set_property("sub-delay", "0.25")
        mp.set_property("secondary-sub-delay", "-0.5")
        expect("independent primary timing", mp.get_property_number("sub-delay"), 0.25)
        expect("independent secondary timing", mp.get_property_number("secondary-sub-delay"), -0.5)
        mp.set_property("sub-pos", "90")
        mp.set_property("secondary-sub-pos", "81")
        expect("primary stays near bottom", mp.get_property_number("sub-pos"), 90)
        expect("secondary sits above primary near bottom", mp.get_property_number("secondary-sub-pos"), 81)
    end)
    if not ok then
        print("FAIL: " .. tostring(failure))
        mp.commandv("quit", "1")
    else
        print("Native dual subtitle tests passed")
        mp.commandv("quit", "0")
    end
end)
''', encoding="utf-8")
        result = subprocess.run([
            "mpv", "--no-config", "--vo=null", "--ao=null", "--pause=yes", "--idle=yes",
            "--input-terminal=no", "--osc=no", "--sid=no", "--secondary-sid=no",
            "--msg-level=all=error,dual_subtitles=info", f"--script={script}", str(media),
        ], capture_output=True, text=True, timeout=30)
        print(result.stdout, end="")
        if result.returncode != 0 or "Native dual subtitle tests passed" not in result.stdout:
            print(result.stderr, end="")
            raise SystemExit(result.returncode or 1)


if __name__ == "__main__":
    main()
