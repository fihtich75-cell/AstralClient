import { world, system, EquipmentSlot } from "@minecraft/server";
import { ModalFormData, FormCancelationReason } from "@minecraft/server-ui";

// Astral HUD: Armor HUD + coordinates + direction + time, shown in the action bar.
// Settings: sneak + use a clock, or run  /scriptevent astral:menu

const DEFAULTS = { armor: true, hand: true, coords: true, dir: true, time: false };
const cache = new Map();

function getCfg(p) {
  let c = cache.get(p.id);
  if (c) return c;
  c = Object.assign({}, DEFAULTS);
  try {
    const s = p.getDynamicProperty("astral:cfg");
    if (typeof s === "string") Object.assign(c, JSON.parse(s));
  } catch (e) { /* defaults */ }
  cache.set(p.id, c);
  return c;
}

function setCfg(p, c) {
  cache.set(p.id, c);
  try { p.setDynamicProperty("astral:cfg", JSON.stringify(c)); } catch (e) { /* not saved */ }
}

world.afterEvents.playerLeave.subscribe((ev) => cache.delete(ev.playerId));

function pctColor(pct) {
  if (pct === null) return "§7";
  if (pct > 60) return "§a";
  if (pct > 30) return "§e";
  return "§c";
}

function durText(label, item) {
  if (!item) return "§8" + label + " —";
  const d = item.getComponent("minecraft:durability");
  if (!d || !d.maxDurability) return "§f" + label + " §7ok";
  const pct = Math.max(0, Math.round(((d.maxDurability - d.damage) * 100) / d.maxDurability));
  return pctColor(pct) + label + " " + pct + "%";
}

const DIRS = ["Ю", "ЮЗ", "З", "СЗ", "С", "СВ", "В", "ЮВ"]; // yaw 0 = south, 90 = west

function hud(p) {
  const c = getCfg(p);
  const lines = [];
  if (c.armor) {
    const eq = p.getComponent("minecraft:equippable");
    if (eq) {
      lines.push([
        durText("Шлем", eq.getEquipment(EquipmentSlot.Head)),
        durText("Нагр", eq.getEquipment(EquipmentSlot.Chest)),
        durText("Поножи", eq.getEquipment(EquipmentSlot.Legs)),
        durText("Ботинки", eq.getEquipment(EquipmentSlot.Feet)),
      ].join("  "));
    }
  }
  if (c.hand) {
    const eq = p.getComponent("minecraft:equippable");
    const it = eq ? eq.getEquipment(EquipmentSlot.Mainhand) : undefined;
    if (it) {
      const d = it.getComponent("minecraft:durability");
      if (d && d.maxDurability) lines.push(durText("В руке", it));
    }
  }
  const info = [];
  if (c.coords) {
    const l = p.location;
    info.push("§fXYZ §b" + Math.floor(l.x) + " " + Math.floor(l.y) + " " + Math.floor(l.z));
  }
  if (c.dir) {
    const yaw = (p.getRotation().y + 360) % 360;
    info.push("§f" + DIRS[Math.round(yaw / 45) % 8]);
  }
  if (c.time) {
    const t = world.getTimeOfDay();
    const h = Math.floor(t / 1000 + 6) % 24;
    const m = Math.floor(((t % 1000) / 1000) * 60);
    info.push("§f" + String(h).padStart(2, "0") + ":" + String(m).padStart(2, "0"));
  }
  if (info.length) lines.push(info.join("  §8|  "));
  return lines.join("\n");
}

system.runInterval(() => {
  for (const p of world.getAllPlayers()) {
    try {
      const text = hud(p);
      if (text) p.onScreenDisplay.setActionBar(text);
    } catch (e) { /* player not ready */ }
  }
}, 10);

function openMenu(p, tries = 0) {
  const c = getCfg(p);
  const form = new ModalFormData()
    .title("Astral HUD")
    .toggle("Armor HUD", { defaultValue: c.armor })
    .toggle("Прочность предмета в руке", { defaultValue: c.hand })
    .toggle("Координаты", { defaultValue: c.coords })
    .toggle("Направление", { defaultValue: c.dir })
    .toggle("Время", { defaultValue: c.time });
  form.show(p).then((r) => {
    if (r.canceled) {
      // chat or another screen was open: try again for ~10 s
      if (r.cancelationReason === FormCancelationReason.UserBusy && tries < 20) {
        system.runTimeout(() => openMenu(p, tries + 1), 10);
      }
      return;
    }
    const v = r.formValues;
    setCfg(p, { armor: !!v[0], hand: !!v[1], coords: !!v[2], dir: !!v[3], time: !!v[4] });
  }).catch(() => {});
}

world.afterEvents.itemUse.subscribe((ev) => {
  if (ev.itemStack.typeId === "minecraft:clock" && ev.source.isSneaking) openMenu(ev.source);
});

system.afterEvents.scriptEventReceive.subscribe((ev) => {
  if (ev.id === "astral:menu" && ev.sourceEntity && ev.sourceEntity.typeId === "minecraft:player") {
    openMenu(ev.sourceEntity);
  }
}, { namespaces: ["astral"] });
