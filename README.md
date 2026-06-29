<div align="center">

<h1>vUnstable</h1>

[![Version](https://img.shields.io/modrinth/v/vunstable?label=Version&color=24b47e)](https://modrinth.com/plugin/vunstable)
[![Downloads](https://img.shields.io/modrinth/dt/vunstable?label=Downloads&color=24b47e)](https://modrinth.com/plugin/vunstable)
[![License](https://img.shields.io/badge/License-vProLabs%20General%20License-blue)](https://www.vprolabs.xyz/projects/license/raw)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://www.oracle.com/java/)
[![Platform](https://img.shields.io/badge/Platform-Paper%20%2F%20Folia%20%2F%20Spigot-red)](https://papermc.io)

<p>The Ultimate Orbital Strike Cannon plugin for Paper/Folia 1.21.x</p>
<p>2000 TNT with intelligent auto-optimization, synchronized explosions, and zero server freeze.</p>

</div>

---

### Features

- **Nuke Rod**, Orbital strike with 2000 TNT entities, 10 concentric rings, 100x100 destruction area
- **Stab Rod**, Vertical shaft borer with INSTANT or FALL modes
- **Queue System**, Fire multiple nukes sequentially without spam
- **Auto-Optimization**, Automatically adjusts `spigot.yml` for optimal performance
- **Folia Support**, Full compatibility with regionized threading
- **Zero Dependencies**, Runs standalone with NMS MethodHandles

---

### Commands

| Command | Description | Permission |
|---------|-------------|------------|
| `/vu give nuke [player]` | Give nuke rod | `vunstable.use` / `vunstable.give.others` |
| `/vu give stab [player]` | Give stab rod | `vunstable.use` / `vunstable.give.others` |
| `/vu update` | Check for updates | `vunstable.admin` |
| `/vu status` | Check optimization status | `vunstable.admin` |
| `/vu reload` | Reload configuration | `vunstable.admin` |

*Alias: `/vunstable` = `/vu`*

---

### Permissions

| Permission | Description | Default |
|------------|-------------|---------|
| `vunstable.use` | Get rods for yourself | op |
| `vunstable.give.others` | Give rods to others | op |
| `vunstable.admin` | Admin commands | op |

---

### Configuration

<details>
<summary><b>View config.yml</b></summary>

```yaml
# Auto-optimize spigot.yml max-tnt-per-tick for Nuke Rod
auto-optimize-spigot: true

nuke:
  total-tnt: 2000
  rings: 10
  min-radius: 5
  max-radius: 50
  spawn-height: 67
  velocity-y: -3.0
  spawn-rate-per-tick: 200
  sync-explosions: true
  base-delay-ticks: 20
  max-concurrent: 1
  queue-size: 3

stab:
  depth: 100
  velocity: -10.0
  fuse-ticks: 10
  spawn-mode: INSTANT
```

</details>

---

### Links

- 🌐 **Website:** https://vprolabs.xyz
- 💬 **Discord:** https://discord.gg/SNzUYWbc5Q
- 📦 **Modrinth:** https://modrinth.com/plugin/vunstable
- ☕ **Support:** https://ko-fi.com/v4bi

---

### License

This project is licensed under the **vProLabs General License**.

- Non-Commercial Use Only
- Attribution Required
- Share Alike
- [View Full License](https://www.vprolabs.xyz/projects/license/raw)

---

<div align="center">

<sub>Made with 🔥 by <strong>vProLabs</strong></sub>

</div>
