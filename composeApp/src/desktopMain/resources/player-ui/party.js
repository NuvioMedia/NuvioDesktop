const partyButton = document.getElementById("partyButton");
const partyPill = document.getElementById("partyPill");
const partyPillStack = document.getElementById("partyPillStack");
const partyPillText = document.getElementById("partyPillText");
const partyModal = document.getElementById("partyModal");
const partyPanel = partyModal.querySelector(".party-panel");
const partyPanelTitle = document.getElementById("partyPanelTitle");
const partyCloseButton = document.getElementById("partyCloseButton");
const partyBody = document.getElementById("partyBody");
const partyConfirm = document.getElementById("partyConfirm");
const partyConfirmTitle = document.getElementById("partyConfirmTitle");
const partyConfirmMessage = document.getElementById("partyConfirmMessage");
const partyConfirmCancel = document.getElementById("partyConfirmCancel");
const partyConfirmAccept = document.getElementById("partyConfirmAccept");
const partyPillBadge = document.getElementById("partyPillBadge");
const partyTabs = document.getElementById("partyTabs");
const partyTabIndicator = document.getElementById("partyTabIndicator");
const partyTabParty = document.getElementById("partyTabParty");
const partyTabChat = document.getElementById("partyTabChat");
const partyTabBadge = document.getElementById("partyTabBadge");
const partyChatBox = document.getElementById("partyChat");
const partyChatViewport = document.getElementById("partyChatViewport");
const partyChatScroll = document.getElementById("partyChatScroll");
const partyChatList = document.getElementById("partyChatList");
const partyChatEmpty = document.getElementById("partyChatEmpty");
const partyChatEmptyTitle = document.getElementById("partyChatEmptyTitle");
const partyChatEmptyMessage = document.getElementById("partyChatEmptyMessage");
const partyChatNew = document.getElementById("partyChatNew");
const partyChatNewLabel = document.getElementById("partyChatNewLabel");
const partyChatInput = document.getElementById("partyChatInput");
const partyChatSend = document.getElementById("partyChatSend");
const partyCodeLength = 6;
const partyChatAvatarSize = 28;
const partyChatMaxLength = 500;
const partyChatMaxInputHeight = 102;
const partyChatCompactHeight = 150;
const partyChatBottomSlack = 24;
const partyChatScrollMs = 300;
const partyCopiedDurationMs = 1800;
const partyMessageDurationMs = 2500;
const partySwitchSettleMs = 1500;

let partyPillKey = "";
let partyPanelKey = "";
let partyKnownMemberIds = null;
let partyCopiedUntil = 0;
let partyCopiedTimer = 0;
let partyConfirmHandler = null;
let partyTab = "party";
let partyChatSentVisible = false;
let partyChatReady = false;
let partyChatNewestKey = "";
let partyChatNewBelow = false;
let partyChatScrollFrame = 0;
let partyChatSignature = "";
let partyChatComposerFrame = 0;
const partyChatRows = new Map();

const currentParty = () => (state.party && typeof state.party === "object" ? state.party : {});

const partyLabel = (key, fallback = "") => {
  const labels = currentParty().labels;
  const value = labels && typeof labels === "object" ? labels[key] : "";
  return String(value || fallback);
};

const partyMembers = () => normalizeItems(currentParty().members);

const currentPartyChat = () => {
  const chat = currentParty().chat;
  return chat && typeof chat === "object" ? chat : {};
};

const partyUnreadCount = () => (currentParty().active ? Math.max(0, Number(currentPartyChat().unread) || 0) : 0);

const partyElement = (tag, className = "", text = null) => {
  const element = document.createElement(tag);
  if (className) element.className = className;
  if (text !== null) element.textContent = text;
  return element;
};

const partyIcon = name => {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", `#${name}`);
  svg.appendChild(use);
  return svg;
};

const partyAction = (element, focusKey, onClick) => {
  element.type = "button";
  element.dataset.partyFocus = focusKey;
  element.addEventListener("click", event => {
    event.stopPropagation();
    onClick();
  });
  return element;
};

const partyButtonElement = (label, variant, focusKey, onClick) => {
  const button = partyElement("button", variant ? `party-button ${variant}` : "party-button");
  button.appendChild(partyElement("span", "", label));
  return partyAction(button, focusKey, onClick);
};

const partyIconButton = (icon, label, className, focusKey, onClick) => {
  const button = partyElement("button", className ? `party-icon-button ${className}` : "party-icon-button");
  button.setAttribute("aria-label", label);
  button.title = label;
  button.appendChild(partyIcon(icon));
  return partyAction(button, focusKey, onClick);
};

const partyAvatarColor = value => {
  const color = String(value || "").trim();
  return /^#[0-9a-fA-F]{6}$/.test(color) ? color : "";
};

const buildPartyAvatar = (member, size, presence = "") => {
  const avatar = partyElement("span", "party-avatar");
  avatar.style.setProperty("--party-avatar-size", `${size}px`);
  const face = partyElement("span", "party-avatar-face", member.initial || "");
  const color = partyAvatarColor(member.color);
  if (color) face.style.background = color;
  const imageUrl = String(member.imageUrl || "").trim();
  if (imageUrl) {
    const image = document.createElement("img");
    image.alt = "";
    image.decoding = "async";
    image.addEventListener("error", () => image.classList.add("image-error"));
    image.src = imageUrl;
    face.appendChild(image);
  }
  avatar.appendChild(face);
  if (presence) avatar.appendChild(partyElement("span", `party-presence ${presence}`));
  return avatar;
};

const buildPartyOpenSeat = size => {
  const seat = partyElement("span", "party-open-seat");
  seat.style.setProperty("--party-avatar-size", `${size}px`);
  seat.appendChild(partyIcon("icon-add"));
  return seat;
};

const buildPartyStack = (container, members, size) => {
  container.textContent = "";
  const shown = members.slice(0, 3);
  const extra = members.length - shown.length;
  const slots = shown.length + (extra > 0 ? 1 : 0);
  const step = size * 0.7;
  container.style.width = `${size + step * Math.max(0, slots - 1)}px`;
  container.style.height = `${size}px`;
  const appendSlot = (index, content) => {
    const slot = partyElement("span", "party-stack-slot");
    slot.style.left = `${step * index}px`;
    slot.style.zIndex = String(slots - index);
    slot.appendChild(content);
    container.appendChild(slot);
  };
  shown.forEach((member, index) => appendSlot(index, buildPartyAvatar(member, size)));
  if (extra > 0) {
    const more = partyElement("span", "party-avatar");
    more.style.setProperty("--party-avatar-size", `${size}px`);
    more.appendChild(partyElement("span", "party-avatar-face extra", `+${extra}`));
    appendSlot(shown.length, more);
  }
};

const updatePartyBadge = (badge, count) => {
  const label = badge.querySelector(".party-unread-count");
  window.clearTimeout(badge.hideTimer);
  if (count > 0) {
    label.textContent = count > 9 ? "9+" : String(count);
    if (badge.hidden || badge.classList.contains("leaving")) {
      badge.hidden = false;
      badge.classList.remove("leaving", "entering");
      void badge.offsetWidth;
      badge.classList.add("entering");
    }
    return;
  }
  if (badge.hidden || badge.classList.contains("leaving")) return;
  badge.classList.add("leaving");
  badge.hideTimer = window.setTimeout(() => {
    badge.hidden = true;
    badge.classList.remove("leaving", "entering");
  }, 160);
};

const renderPartyChrome = () => {
  const party = currentParty();
  const title = partyLabel("title", "Watch party");
  partyButton.hidden = !party.available;
  partyButton.classList.toggle("party-active", Boolean(party.active));
  partyButton.setAttribute("aria-label", title);
  partyButton.setAttribute("title", title);

  const members = partyMembers();
  const message = String(party.pillMessage || "").trim();
  const visible = Boolean(
    party.active &&
    members.length &&
    (state.controlsVisible || party.pillPinned) &&
    !activeModal &&
    !state.isInPip,
  );
  root.classList.toggle("party-pill-visible", visible);
  partyPill.classList.toggle("visible", visible);
  partyPill.setAttribute("aria-hidden", visible ? "false" : "true");
  partyPill.tabIndex = visible ? 0 : -1;
  partyPill.setAttribute("aria-label", message || title);

  updatePartyBadge(partyPillBadge, partyUnreadCount());

  const stackKey = members.map(member => [member.id, member.initial, member.imageUrl, member.color].join(":")).join("|");
  if (stackKey !== partyPillKey) {
    partyPillKey = stackKey;
    buildPartyStack(partyPillStack, members, 28);
  }
  if (partyPillText.textContent !== message) {
    partyPillText.textContent = message;
    partyPillText.classList.remove("changed");
    if (message) {
      void partyPillText.offsetWidth;
      partyPillText.classList.add("changed");
    }
  }
};

const copyPartyInvite = () => {
  send("partyCopyInvite", 0);
  partyCopiedUntil = Date.now() + partyCopiedDurationMs;
  window.clearTimeout(partyCopiedTimer);
  partyCopiedTimer = window.setTimeout(() => {
    if (activeModal === "party") renderPartyModal();
  }, partyCopiedDurationMs + 20);
  if (activeModal === "party") renderPartyModal();
};

const showPartyConfirm = ({ title, message, confirmLabel, danger, onConfirm }) => {
  partyConfirmHandler = onConfirm;
  partyConfirmTitle.textContent = title || "";
  partyConfirmMessage.textContent = message || "";
  partyConfirmMessage.hidden = !message;
  partyConfirmCancel.textContent = partyLabel("cancel", "Cancel");
  partyConfirmAccept.textContent = confirmLabel || "";
  partyConfirmAccept.classList.toggle("danger", Boolean(danger));
  partyConfirmAccept.classList.toggle("primary", !danger);
  partyPanel.inert = true;
  partyConfirm.hidden = false;
  partyConfirmAccept.focus({ preventScroll: true });
};

const closePartyConfirm = () => {
  partyConfirmHandler = null;
  partyConfirm.hidden = true;
  partyPanel.inert = false;
};

const isPartyConfirmOpen = () => activeModal === "party" && !partyConfirm.hidden;

const buildPartyStart = party => {
  const content = partyElement("div", "party-start");
  const hero = partyElement("div", "party-seats-hero");
  const step = 56 * 0.72;
  [2, 1].forEach(index => {
    const seat = buildPartyOpenSeat(56);
    seat.style.left = `${step * index}px`;
    hero.appendChild(seat);
  });
  const profile = buildPartyAvatar(party.profile || {}, 56);
  profile.style.left = "0";
  hero.appendChild(profile);
  content.appendChild(hero);
  content.appendChild(partyElement("h2", "party-start-title", partyLabel("startTitle")));
  content.appendChild(partyElement("p", "party-text", partyLabel("startDescription")));
  const note = String(party.startNote || "").trim();
  if (note) {
    content.appendChild(partyElement("p", "party-note", note));
  } else {
    const start = partyButtonElement(partyLabel("startAction"), "primary party-start-action", "start", () => send("partyStart", 0));
    if (party.busy) {
      start.disabled = true;
      start.prepend(partyElement("span", "party-spinner"));
    }
    content.appendChild(start);
  }
  content.appendChild(partyElement("p", "party-experimental", partyLabel("experimental")));
  return content;
};

const buildPartyCode = party => {
  const section = partyElement("div", "party-code");
  const header = partyElement("div", "party-row");
  if (party.connected) {
    header.appendChild(partyElement("span", "party-label", partyLabel("codeLabel")));
  } else {
    const status = partyElement("span", "party-label accent party-row");
    status.appendChild(partyElement("span", "party-pulsing-dot"));
    status.appendChild(partyElement("span", "", partyLabel("reconnecting")));
    header.appendChild(status);
  }
  const copied = partyCopiedUntil > Date.now();
  header.appendChild(partyIconButton(
    copied ? "icon-check" : "icon-copy",
    partyLabel("copyInvite"),
    copied ? "done" : "",
    "copy",
    copyPartyInvite,
  ));
  section.appendChild(header);
  const slots = partyElement("div", "party-code-slots");
  const code = String(party.code || "");
  for (let index = 0; index < partyCodeLength; index += 1) {
    const slot = partyElement("div", "party-code-slot");
    slot.appendChild(partyElement("span", "party-code-char", code.charAt(index)));
    slot.appendChild(partyElement("span", "party-code-line"));
    slots.appendChild(slot);
  }
  section.appendChild(slots);
  return section;
};

const confirmPartyRemoval = member => showPartyConfirm({
  title: member.removeTitle,
  message: partyLabel("removeMessage"),
  confirmLabel: partyLabel("remove"),
  danger: true,
  onConfirm: () => send(`partyRemoveMember:${member.id}`, 0),
});

const buildPartyMembers = party => {
  const section = partyElement("div", "party-members");
  const members = partyMembers();
  const total = Math.max(0, Number(party.maxMembers) || 0);
  const header = partyElement("div", "party-members-header");
  header.appendChild(partyElement("span", "party-label", party.seatsLabel || ""));
  const dots = partyElement("div", "party-seat-dots");
  for (let index = 0; index < total; index += 1) {
    dots.appendChild(partyElement("span", index < members.length ? "party-seat-dot filled" : "party-seat-dot"));
  }
  header.appendChild(dots);
  section.appendChild(header);

  const known = partyKnownMemberIds;
  members.forEach(member => {
    const entering = known !== null && !known.has(member.id);
    const row = partyElement("div", entering ? "party-member-row entering" : "party-member-row");
    row.appendChild(buildPartyAvatar(member, 44, member.presence || "offline"));
    const copy = partyElement("div", "party-member-copy");
    const nameRow = partyElement("div", "party-row");
    nameRow.appendChild(partyElement("span", "party-member-name", member.name || ""));
    if (member.isHost) nameRow.appendChild(partyElement("span", "party-host-tag", partyLabel("host")));
    copy.appendChild(nameRow);
    copy.appendChild(partyElement("span", "party-member-status", member.status || ""));
    row.appendChild(copy);
    if (member.canRemove) {
      row.appendChild(partyIconButton("icon-remove", partyLabel("remove"), "muted", `remove:${member.id}`, () => confirmPartyRemoval(member)));
    }
    section.appendChild(row);
  });
  partyKnownMemberIds = new Set(members.map(member => member.id));

  if (members.length < total) {
    const invite = partyAction(partyElement("button", "party-open-seat-row"), "invite", copyPartyInvite);
    invite.appendChild(buildPartyOpenSeat(44));
    invite.appendChild(partyElement("span", "party-open-seat-label", partyLabel("openSeat")));
    section.appendChild(invite);
  }
  return section;
};

const buildPartySwitch = (key, title, description, checked, eventType) => {
  const row = partyAction(partyElement("button", "party-switch-row"), key, () => {
    const next = row.getAttribute("aria-checked") !== "true";
    row.setAttribute("aria-checked", String(next));
    send(eventType, next ? 1 : 0);
    window.setTimeout(() => {
      partyPanelKey = "";
      if (activeModal === "party") renderPartyModal();
    }, partySwitchSettleMs);
  });
  row.setAttribute("role", "switch");
  row.setAttribute("aria-checked", String(Boolean(checked)));
  const copy = partyElement("span", "party-switch-copy");
  copy.appendChild(partyElement("span", "party-switch-title", title));
  copy.appendChild(partyElement("span", "party-switch-description", description));
  row.appendChild(copy);
  row.appendChild(partyElement("span", "party-switch"));
  return row;
};

const buildPartyHostSettings = party => {
  const section = partyElement("div", "party-host-settings");
  section.appendChild(buildPartySwitch("hostOnly", partyLabel("hostOnlyTitle"), partyLabel("hostOnlyDescription"), party.hostOnly, "partyHostOnly"));
  section.appendChild(buildPartySwitch("sharing", partyLabel("shareTitle"), partyLabel("shareDescription"), party.sharing, "partySharing"));
  const note = String(party.shareNote || "").trim();
  if (note) section.appendChild(partyElement("p", "party-note", note));
  return section;
};

const closeAndSendParty = type => {
  closePlayerModal();
  send(type, 0);
};

const buildPartyContent = party => {
  const content = partyElement("div", "party-content");
  const behind = String(party.behindTitle || "").trim();
  if (behind) {
    const section = partyElement("div", "party-section");
    section.appendChild(partyElement("p", "party-text primary", behind));
    section.appendChild(partyButtonElement(partyLabel("catchUp"), "primary", "catchUp", () => closeAndSendParty("partyCatchUp")));
    content.appendChild(section);
  }

  const overview = partyElement("div", "party-overview");
  overview.appendChild(buildPartyCode(party));
  overview.appendChild(buildPartyMembers(party));
  content.appendChild(overview);

  if (party.isHost) content.appendChild(buildPartyHostSettings(party));
  if (!party.isHost && party.hostOnly) {
    const note = partyElement("div", "party-host-only-note");
    note.appendChild(partyIcon("icon-lock"));
    note.appendChild(partyElement("span", "", partyLabel("hostOnlyNote")));
    content.appendChild(note);
  }

  const nowWatching = String(party.nowWatching || "").trim();
  if (nowWatching) {
    const section = partyElement("div", "party-section");
    section.appendChild(partyElement("p", "party-text primary", nowWatching));
    section.appendChild(partyButtonElement(partyLabel("watchWithParty"), "primary", "watch", () => closeAndSendParty("partyWatch")));
    const switchLabel = String(party.switchLabel || "").trim();
    if (switchLabel) {
      section.appendChild(partyButtonElement(switchLabel, "", "switch", () => showPartyConfirm({
        title: partyLabel("switchTitle"),
        message: party.switchMessage,
        confirmLabel: partyLabel("switchConfirm"),
        danger: false,
        onConfirm: () => send("partySwitch", 0),
      })));
    }
    content.appendChild(section);
  }

  const actions = partyElement("div", "party-actions");
  actions.appendChild(partyButtonElement(partyLabel("leave"), "", "leave", () => closeAndSendParty("partyLeave")));
  if (party.canEnd) {
    actions.appendChild(partyButtonElement(partyLabel("end"), "danger", "end", () => showPartyConfirm({
      title: partyLabel("endTitle"),
      message: partyLabel("endMessage"),
      confirmLabel: partyLabel("end"),
      danger: true,
      onConfirm: () => closeAndSendParty("partyEnd"),
    })));
  }
  content.appendChild(actions);
  return content;
};

const partyEncodeText = text => {
  let binary = "";
  new TextEncoder().encode(text).forEach(byte => {
    binary += String.fromCharCode(byte);
  });
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
};

const syncPartyChatVisible = () => {
  const wanted = activeModal === "party" && Boolean(currentParty().active) && partyTab === "chat" && !state.isInPip;
  if (wanted === partyChatSentVisible) return;
  partyChatSentVisible = wanted;
  send("partyChatVisible", wanted ? 1 : 0);
};

const partyChatDistanceFromBottom = () =>
  partyChatScroll.scrollHeight - partyChatScroll.scrollTop - partyChatScroll.clientHeight;

const isPartyChatAtBottom = () => partyChatDistanceFromBottom() <= partyChatBottomSlack;

const updatePartyChatNewButton = () => {
  const visible = partyChatNewBelow && !isPartyChatAtBottom();
  if (visible === !partyChatNew.hidden) return;
  if (visible) {
    partyChatNew.hidden = false;
    partyChatNew.classList.remove("leaving");
    void partyChatNew.offsetWidth;
    partyChatNew.classList.add("entering");
    return;
  }
  partyChatNew.classList.remove("entering");
  partyChatNew.classList.add("leaving");
  window.setTimeout(() => {
    if (!partyChatNewBelow || isPartyChatAtBottom()) {
      partyChatNew.hidden = true;
      partyChatNew.classList.remove("leaving");
    }
  }, 160);
};

const scrollPartyChatToBottom = smooth => {
  window.cancelAnimationFrame(partyChatScrollFrame);
  const target = () => partyChatScroll.scrollHeight - partyChatScroll.clientHeight;
  if (!smooth || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    partyChatScroll.scrollTop = target();
    return;
  }
  const from = partyChatScroll.scrollTop;
  const start = performance.now();
  const step = now => {
    const progress = Math.min(1, (now - start) / partyChatScrollMs);
    const eased = 1 - Math.pow(1 - progress, 3);
    partyChatScroll.scrollTop = from + (target() - from) * eased;
    if (progress < 1) partyChatScrollFrame = window.requestAnimationFrame(step);
  };
  partyChatScrollFrame = window.requestAnimationFrame(step);
};

const resetPartyChatList = () => {
  partyChatRows.clear();
  partyChatList.textContent = "";
  partyChatReady = false;
  partyChatSignature = "";
  partyChatNewestKey = "";
  partyChatNewBelow = false;
  partyChatNew.hidden = true;
  partyChatNew.classList.remove("entering", "leaving");
  partyChatEmpty.hidden = true;
};

const buildPartyChatRow = () => {
  const el = partyElement("div", "party-chat-row");
  const time = partyElement("div", "party-chat-time");
  const name = partyElement("div", "party-chat-name");
  const line = partyElement("div", "party-chat-line");
  const avatar = partyElement("span", "party-chat-avatar");
  const bubble = partyElement("div", "party-chat-bubble");
  line.appendChild(avatar);
  line.appendChild(bubble);
  const row = { el, time, name, avatar, bubble, key: "", avatarKey: "" };
  const failed = partyAction(partyElement("button", "party-chat-failed"), "chatRetry", () => send(`partyChatRetry:${row.key}`, 0));
  row.failed = failed;
  el.appendChild(time);
  el.appendChild(name);
  el.appendChild(line);
  el.appendChild(failed);
  return row;
};

const updatePartyChatRow = (row, message) => {
  const mine = Boolean(message.mine);
  row.key = String(message.key || "");
  row.el.classList.toggle("mine", mine);
  row.el.classList.toggle("starts-group", Boolean(message.startsGroup));
  row.el.classList.toggle("ends-group", Boolean(message.endsGroup));
  row.time.hidden = !message.showTime;
  row.time.textContent = message.time || "";
  const showName = Boolean(message.startsGroup) && !mine;
  row.name.hidden = !showName;
  row.name.textContent = showName ? message.name || "" : "";
  row.avatar.hidden = mine;
  const avatarKey = [message.endsGroup ? 1 : 0, message.initial, message.imageUrl, message.color].join("|");
  if (avatarKey !== row.avatarKey) {
    row.avatarKey = avatarKey;
    row.avatar.textContent = "";
    if (message.endsGroup) row.avatar.appendChild(buildPartyAvatar(message, partyChatAvatarSize));
  }
  row.bubble.textContent = message.body || "";
  row.bubble.classList.toggle("pending", !message.sent);
  row.failed.hidden = !message.failed;
  row.failed.textContent = message.failed ? partyLabel("chatNotSent") : "";
};

const syncPartyChatRows = messages => {
  const firstRender = !partyChatReady;
  const wasAtBottom = firstRender || isPartyChatAtBottom();
  const tops = new Map();
  partyChatRows.forEach((row, key) => tops.set(key, row.el.getBoundingClientRect().top));
  const seen = new Set();
  messages.forEach((message, index) => {
    const key = String(message.key || "");
    let row = partyChatRows.get(key);
    const created = !row;
    if (created) {
      row = buildPartyChatRow();
      partyChatRows.set(key, row);
    }
    updatePartyChatRow(row, message);
    seen.add(key);
    const expected = partyChatList.children[index] || null;
    if (expected !== row.el) partyChatList.insertBefore(row.el, expected);
    if (created && !firstRender) {
      row.el.classList.add("entering");
      row.el.addEventListener("animationend", () => row.el.classList.remove("entering"), { once: true });
    }
  });
  Array.from(partyChatRows.keys()).forEach(key => {
    if (seen.has(key)) return;
    partyChatRows.get(key).el.remove();
    partyChatRows.delete(key);
  });

  if (!firstRender && !window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    partyChatRows.forEach((row, key) => {
      const top = tops.get(key);
      if (top === undefined) return;
      const delta = top - row.el.getBoundingClientRect().top;
      if (Math.abs(delta) < 1) return;
      row.el.animate(
        [{ transform: `translateY(${delta}px)` }, { transform: "translateY(0)" }],
        { duration: 300, easing: "cubic-bezier(.2, .8, .2, 1)" },
      );
    });
  }

  const newest = messages[messages.length - 1];
  const newestKey = newest ? String(newest.key || "") : "";
  if (firstRender) {
    scrollPartyChatToBottom(false);
  } else if (newest && newestKey !== partyChatNewestKey) {
    if (newest.mine || wasAtBottom) scrollPartyChatToBottom(true);
    else partyChatNewBelow = true;
  }
  partyChatNewestKey = newestKey;
  partyChatReady = true;
  updatePartyChatNewButton();
};

const updatePartyChatComposer = () => {
  partyChatInput.style.height = "auto";
  partyChatInput.style.height = `${Math.min(partyChatInput.scrollHeight, partyChatMaxInputHeight)}px`;
  partyChatSend.disabled = !partyChatInput.value.trim();
};

const sendPartyChatMessage = () => {
  const text = partyChatInput.value.trim().slice(0, partyChatMaxLength);
  if (!text) return;
  send(`partyChatSend:${partyEncodeText(text)}`, 0);
  partyChatInput.value = "";
  updatePartyChatComposer();
  partyChatInput.focus({ preventScroll: true });
};

const renderPartyChat = visible => {
  partyChatInput.placeholder = partyLabel("chatPlaceholder", "Message");
  partyChatSend.setAttribute("aria-label", partyLabel("chatSend", "Send"));
  partyChatSend.title = partyLabel("chatSend", "Send");
  partyChatNewLabel.textContent = partyLabel("chatNewMessages", "New messages");
  partyChatEmptyTitle.textContent = partyLabel("chatEmptyTitle");
  partyChatEmptyMessage.textContent = partyLabel("chatEmptyMessage");
  const chat = currentPartyChat();
  if (!visible || !chat.ready) {
    if (partyChatReady) resetPartyChatList();
    return;
  }
  const messages = normalizeItems(chat.messages);
  const signature = messages
    .map(message => [message.key, message.sent, message.failed, message.startsGroup, message.endsGroup, message.time, message.name, message.initial, message.imageUrl, message.color, message.body].join("\u0001"))
    .join("\u0002");
  if (signature === partyChatSignature && partyChatReady) return;
  partyChatSignature = signature;
  const firstRender = !partyChatReady;
  syncPartyChatRows(messages);
  partyChatEmpty.hidden = messages.length > 0;
  partyChatEmpty.classList.toggle("compact", partyChatViewport.clientHeight < partyChatCompactHeight);
  if (firstRender) updatePartyChatComposer();
};

const renderPartyTabs = () => {
  const active = Boolean(currentParty().active);
  partyTabs.hidden = !active;
  if (!active) {
    partyTab = "party";
    updatePartyBadge(partyTabBadge, 0);
    return;
  }
  const chat = partyTab === "chat";
  partyTabParty.firstElementChild.textContent = partyLabel("tabParty", "Party");
  partyTabChat.firstElementChild.textContent = partyLabel("tabChat", "Chat");
  partyTabParty.classList.toggle("selected", !chat);
  partyTabChat.classList.toggle("selected", chat);
  partyTabParty.setAttribute("aria-selected", String(!chat));
  partyTabChat.setAttribute("aria-selected", String(chat));
  partyTabIndicator.classList.toggle("chat", chat);
  updatePartyBadge(partyTabBadge, partyUnreadCount());
};

const setPartyTab = tab => {
  if (partyTab === tab) return;
  partyTab = tab;
  if (activeModal === "party") renderPartyModal();
  if (tab === "chat") partyChatInput.focus({ preventScroll: true });
};

const onPartyModalOpened = () => {
  if (partyUnreadCount() > 0) partyTab = "chat";
};

const partyPanelSignature = party => JSON.stringify({
  ...party,
  chat: null,
  pillMessage: "",
  pillPinned: false,
  message: "",
  messageToken: 0,
  panelToken: 0,
  copied: partyCopiedUntil > Date.now(),
});

const renderPartyModal = () => {
  partyPanelTitle.textContent = partyLabel("title", "Watch party");
  partyCloseButton.textContent = partyLabel("close", "Close");
  const party = currentParty();
  renderPartyTabs();
  const chatTab = Boolean(party.active) && partyTab === "chat";
  partyBody.hidden = chatTab;
  partyChatBox.hidden = !chatTab;
  renderPartyChat(chatTab);
  syncPartyChatVisible();
  const key = partyPanelSignature(party);
  if (key === partyPanelKey) return;
  partyPanelKey = key;
  const focusKey = document.activeElement && partyBody.contains(document.activeElement)
    ? document.activeElement.dataset.partyFocus
    : "";
  const scrollTop = partyBody.scrollTop;
  partyBody.textContent = "";
  partyBody.appendChild(party.active ? buildPartyContent(party) : buildPartyStart(party));
  partyBody.scrollTop = scrollTop;
  if (focusKey) {
    const target = Array.from(partyBody.querySelectorAll("[data-party-focus]"))
      .find(element => element.dataset.partyFocus === focusKey);
    if (target) target.focus({ preventScroll: true });
  }
};

const onPartyModalClosed = () => {
  if (partyChatSentVisible) {
    partyChatSentVisible = false;
    send("partyChatVisible", 0);
  }
  resetPartyChatList();
  closePartyConfirm();
  partyPanelKey = "";
  partyKnownMemberIds = null;
};

const applyPartyControls = previous => {
  const party = currentParty();
  const before = previous && typeof previous === "object" ? previous : {};
  const message = String(party.message || "").trim();
  if ((Number(party.messageToken) || 0) !== (Number(before.messageToken) || 0) && message) {
    showPlayerToast(message, { icon: "icon-party", durationMs: partyMessageDurationMs });
  }
  if ((Number(party.panelToken) || 0) !== (Number(before.panelToken) || 0) && activeModal !== "party" && !state.isInPip) {
    openPlayerModal("party");
  }
  if (!party.active && before.active) closePartyConfirm();
};

partyTabParty.addEventListener("click", event => {
  event.stopPropagation();
  setPartyTab("party");
});

partyTabChat.addEventListener("click", event => {
  event.stopPropagation();
  setPartyTab("chat");
});

partyChatInput.addEventListener("input", () => {
  partyChatSend.disabled = !partyChatInput.value.trim();
  if (partyChatComposerFrame) return;
  partyChatComposerFrame = window.requestAnimationFrame(() => {
    partyChatComposerFrame = 0;
    updatePartyChatComposer();
  });
});

partyChatInput.addEventListener("keydown", event => {
  if (event.key !== "Enter" || event.shiftKey || event.isComposing) return;
  event.preventDefault();
  sendPartyChatMessage();
});

partyChatSend.addEventListener("click", event => {
  event.stopPropagation();
  sendPartyChatMessage();
});

partyChatNew.addEventListener("click", event => {
  event.stopPropagation();
  partyChatNewBelow = false;
  updatePartyChatNewButton();
  scrollPartyChatToBottom(true);
});

partyChatScroll.addEventListener("scroll", () => {
  if (isPartyChatAtBottom()) partyChatNewBelow = false;
  updatePartyChatNewButton();
}, { passive: true });

partyChatViewport.addEventListener("click", event => {
  if (event.target.closest("button")) return;
  partyChatInput.blur();
});

if (window.ResizeObserver) {
  new ResizeObserver(() => {
    partyChatEmpty.classList.toggle("compact", partyChatViewport.clientHeight < partyChatCompactHeight);
  }).observe(partyChatViewport);
}

partyButton.addEventListener("click", event => {
  event.stopPropagation();
  noteChromeActivity(true);
  openPlayerModal("party");
});

partyPill.addEventListener("click", event => {
  event.stopPropagation();
  openPlayerModal("party");
});

partyCloseButton.addEventListener("click", event => {
  event.stopPropagation();
  closePlayerModal();
});

partyConfirm.addEventListener("click", event => {
  event.stopPropagation();
  if (event.target === partyConfirm) closePartyConfirm();
});

partyConfirmCancel.addEventListener("click", event => {
  event.stopPropagation();
  closePartyConfirm();
});

partyConfirmAccept.addEventListener("click", event => {
  event.stopPropagation();
  const handler = partyConfirmHandler;
  closePartyConfirm();
  if (handler) handler();
});
