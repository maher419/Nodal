# Nodal — A Decentralized Social Network (P2P)

> **Complete architecture reference** — Last updated: August 2026
> Every decision here is based on hands-on verification of the `jvm-libp2p 1.3.5-RELEASE` library (latest release, Cloudsmith)
> Arabic version: [IDEA.md](IDEA.md)

---

## 1. Vision

A **fully decentralized** social network and content-sharing platform: no central server owns the data or controls the connections.
Every device (phone/computer) is a node acting as both client and server. Inspired by Twitter in simplicity and interaction speed.

- **Censorship resistance**: no central authority deletes or blocks content
- **Privacy & security**: no central database to breach, and connections are always encrypted
- **User ownership**: your data lives with you and with whoever you choose to share it with
- **Resilience**: the service keeps running even if nodes leave the network

---

## 2. Core Principles (Non-Negotiable)

1. **No official copy of anything** — no central database, no "source of truth"
2. **Identity = key** (Ed25519) — data is *signed*, not certified by an authority
3. **Every device is a node** (client + server) — storage is distributed among those who care about the content
4. **Everything is a signed post of a specific type** — post / profile / list / like / follow / block
5. **Infrastructure exists but is neutral** — bootstrap/relay nodes relay, they don't control or delete
6. **Local-First** — local storage is a cache and archive, not an authority

---

## 3. Architecture

### Modules
| Module | Technology | Role |
|---|---|---|
| `core` | Kotlin/JVM (KMP-ready later) | identity, CID, signing, node, protocols, feed |
| `bootstrap` | Kotlin/JVM | infrastructure node: rendezvous + relay + ring memory + optional index |
| `android` | Kotlin + Jetpack Compose (Arabic RTL) | UI: timeline / compose / profile / people / settings |

### The Three Layers (functional distribution — no layer owns everything)
| Layer | Role | Data ownership |
|---|---|---|
| **User device** | Keystore + local cache + live memory | its key + an archive of what it has seen |
| **Peers** | GossipSub + block exchange + distributed queries | copies of what matters to them |
| **bootstrap** | rendezvous + relay + shallow ring memory + optional address index | owns nothing permanent — shallow temporary memory |

---

## 4. Identity & Cryptography

- **Key**: Ed25519 — generated inside the **Android Keystore** on Android (never extracted) + password-encrypted backup
- **Identifier (PeerId)**: multihash(identity) of the protobuf-encoded public key — self-computed from the key, with no authority
- **Signing**: Ed25519 over **canonical JSON** (CanonicalJson: alphabetically sorted keys, UTF-8, integers without stray fractions)
- **Verification**: extract the public key from the PeerId (manual protobuf parsing) then `verify` — no service call
- **E2EE (M5)**: X25519 + ChaCha20-Poly1305 over the existing Noise layer — for private messaging only; public posts are signed, not encrypted

---

## 5. Content Addressing (CID) — From Scratch

| Type | Structure | Use |
|---|---|---|
| CIDv0 | base58btc(0x12 0x20 sha256(data)) | post identifiers (hash of canonical JSON) |
| CIDv1 raw | base32(0x01 0x55 0x12 0x20 sha256(data)) | media and block identifiers |

- Implementation from scratch: Base58btc + Base32 (RFC4648) + Multihash (~100 lines) — no external libraries
- Self-describing CID: any post = hash of its content → **natural deduplication** and automatic integrity verification

---

## 6. Networking (libp2p)

### Stack
- **Transport**: TCP
- **Encryption**: Noise (XX) — mandatory for every connection
- **Multiplexing**: mplex
- **PubSub**: GossipSub v1.x
- **Auxiliary protocols**: Identify + Ping
- **mDNS**: automatic discovery on the same Wi-Fi network
- **Relay**: circuit relay via bootstrap (for NAT peers) — beta in jvm-libp2p, being tested in practice

### Topics
| Topic | Use |
|---|---|
| `/nodal/u/<peerId>` | user topic — following = subscribing |
| `/nodal/feed/1.0.0` | global feed — discovery |

### Known Constraints (documented from library inspection)
- **No Kademlia DHT in jvm-libp2p** → discovery: bootstrap + mDNS + manual add + from content
- No WebRTC → NAT peers via relay only (M1–M4), WebRTC in M6

---

## 7. Timeline — Push + Pull + Local Index

```
push: GossipSub subscription to followed topics → arrives instantly
pull: on open → /nodal/sync/1.0.0 "everything after my cursor" from bootstrap + peers
merge: dedupe by CID → descending chronological order → cap of 500
catch-up: ring memory at bootstrap (last 500 posts per active topic)
```

- Every incoming post is verified (signature + ±5-minute time window + 64KB size cap) before acceptance
- `seq` is used for internal ordering and counters only — **there is no strict ascending check yet**: the sync path (catch-up pull) fetches full history, and a strict check would reject old-seq posts arriving late. (Strict seq verification requires a (seq, createdAt) cursor per author — deferred to M2 with the local cache.)
- Followers = a set of PeerIds in memory/cache, built from observed `follow` posts and manual additions

---

## 8. Peer Discovery & Adding People (5 Ways)

1. **Built-in bootstrap**: an address list shipped in the app, connected on first run
2. **mDNS**: automatic on the same local network
3. **Manual**: paste/scan `multiaddr + PeerId`
4. **From content**: any received post carries its author's identity → a "Follow" button adds them instantly
5. **Signed lists (list)**: a post carrying a group of identifiers → follow everyone in one tap (the primary discovery mechanism)

---

## 9. Finding People — Three Graduated Layers

1. **Local**: in the node's cache (follows, people who messaged you, what arrived via pubsub) — instant, no network
2. **Distributed**: `/nodal/search/1.0.0` — query to connected peers with a TTL hop limit; each node searches locally and replies; results are merged (Gnutella technique)
3. **Optional index**: bootstrap offers an **addresses-only** index (name/id/avatar CID — no content) — optional and replaceable, like choosing a DNS provider

**The honest limit**: "network bubble" search, not universal search — an accepted trade-off without a central index.

---

## 10. Fame & Trending — No Central Algorithm

| Mechanism | Role |
|---|---|
| **Signed curated lists** | the base: "tech celebrities" = a list post; trust flows through the social graph |
| **Seed accounts** | a set of accounts on first run, their content replicated across many nodes = always available |
| **Local counters** | each node counts the likes/reposts it has seen; "trending" = merging replies from connected peers |

Fame = who many people follow and whose content replicates — not who an algorithm promotes.

---

## 11. Nodal Protocols (Versions)

| Protocol | Purpose | Details | Stage |
|---|---|---|---|
| `/nodal/blocks/1.0.0` | block exchange (IPFS-style) | request: CID → reply: block or NOT_FOUND | M1 |
| `/nodal/sync/1.0.0` | pull posts since a cursor | request: {topics[], since} → reply: {posts[]} | M1 |
| `/nodal/search/1.0.0` | distributed query | request: {query, ttl} → reply: {results[]} | M2 |
| `/nodal/dm/1.0.0` | E2EE private messaging | X25519 key + ChaCha20-Poly1305 | M5 |

**Unified framing**: every protocol = canonical JSON with length-prefixed framing (4-byte length + UTF-8 payload).

---

## 12. Post Types

| type | Key fields | Use |
|---|---|---|
| `post` | text, media[] | core content (text/images/files) |
| `profile` | text (bio), media[0]=avatar | the profile — published on your topic |
| `list` | payload (JSON: entries[]) | signed celebrity/topic lists |
| `follow` | ref = followed PeerId | open, syncable relationship graph |
| `like` | ref = post CID | countable like |
| `block` | payload (JSON: entries[]) | personal blocklist — the user's right |

---

## 13. Roles & Security

- **bootstrap does not sign, modify, or delete** — it only relays. Users are legally responsible for their content (no intermediary)
- **Mandatory receive-time verification**: signature → time window → 64KB size cap (pubsub and sync alike)
- **Flood protection**: 10MB block size cap (enforced) + per-author publish rate limit (M2 — requires persistent storage)
- **Moderation**: signed blocklists + communities with private topics (subscription = accepting the rules)
- **Compression**: optional gzip for large blocks in M4

---

## 14. Battery & Data

- Connection active only while the app is open — **no permanent background service** (M1–M3)
- Large media: pull on Wi-Fi only
- A time cursor (cursor) per channel — never a full sync

---

## 15. Storage

| Stage | Storage | Reason |
|---|---|---|
| M1–M3 | **memory only + key file** (no database!) | proof of concept — a "living river" as in the concept |
| M4 | local Room cache (offline reading archive) | **cache, not authority** — no official copy |

---

## 16. Roadmap

| Stage | Content | Success criterion |
|---|---|---|
| **M1** | core: identity + CID + signing + node + blocks + sync + in-memory feed + two-node local test | a signed post travels from one node to another and verifies |
| **M2** | full bootstrap: ring memory + relay + rendezvous + address index | two nodes over the real internet (no localhost) |
| **M3** | Android Compose RTL: timeline / compose / profile / people / settings | APK working on two devices |
| **M4** | local cache + offline reading + Wi-Fi-only media | scrolling history offline |
| **M5** | E2EE + communities + lists UI | a private message unreadable by bootstrap |
| **M6** | WebRTC + KMP port (iOS/web) | the same core on two platforms |

---

## 17. Honest Limitations (Accepted Trade-offs — We Don't Hide Them)

- ❌ **bubble** search, not universal (impossible without a central index)
- ❌ **local-relative** trending, not universal
- ❌ availability = replication — no guarantees (an unreplicated node disappears)
- ❌ no universal archive — history ends at the edges of replication
- ⚠️ first-run success depends on bootstrap quality (optional, multiple, open source)

---

## 18. Definition of Final Success

1. Works on two devices over the **real internet** (no localhost)
2. Timeline displays **signed and verified** posts
3. Taking down any bootstrap does not kill the network (if an alternative exists)
4. No single point of control — no server, no provider, no authority
