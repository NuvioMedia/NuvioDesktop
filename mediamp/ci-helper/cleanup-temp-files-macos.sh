#!/bin/zsh

#
# Copyright (C) 2024-2025 OpenAni and contributors.
#
# Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
#
# https://github.com/open-ani/mediamp/blob/main/LICENSE
#

local TARGET_DIR

if [[ -n "$TMPDIR" ]]; then
  echo "env TMPDIR exists."
  TARGET_DIR="$TMPDIR"
else
  TARGET_DIR="/private/var/folders/fv/b5h2b9f577q7z5j0r5n8n6g40000gp/T"
  echo "env TMPDIR does not exist. Using $TARGET_DIR"
fi

# clear "debuginfo.knd1145141919810.tmp"
# strings.knt1145141919810.tmp
find $TARGET_DIR -type f -name "*.kn*.tmp" -exec rm {} + -print 2>/dev/null | wc -l