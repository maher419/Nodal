# Nodal — A Decentralized Social Network (P2P)

> **Complete Architecture Reference**
> *Last updated: August 2026*
> *Implementation status:* Verified against `jvm-libp2p:1.3.5-RELEASE` (Cloudsmith).
> *Arabic Version:* [IDEA.md](https://www.google.com/search?q=./IDEA.md)

---

## 1. Vision & Overview

**Nodal** is a fully decentralized, serverless social network and content-sharing platform inspired by Twitter's simplicity and speed.

* **Censorship-Resistant:** No central authority can delete content or block users.
* **Privacy & Security:** End-to-end network encryption with zero central database risks.
* **User Ownership:** Data resides exclusively with the user and their peer graph.
* **Resilience:** The network remains active even if individual nodes or bootstrap servers go offline.

---

## 2. Core Non-Negotiable Principles

* **No Source of Truth:** No official central server or master database exists.
* **Identity = Ed25519 Key:** Data is cryptographically signed, requiring no third-party certification.
* **Every Device is a Node:** Storage and distribution are handled directly by active peers.
* **Typed Signed Posts:** Profiles, posts, likes, follows, and blocks are all typed, signed events.
* **Neutral Infrastructure:** Relay and bootstrap nodes purely route traffic without administrative power.
* **Local-First Architecture:** Local device storage serves as an archive, not a central authority.

---

## 3. System Architecture & Layers

### Architecture Modules

| Module | Technology | Primary Role |
| --- | --- | --- |
| **`core`** | Kotlin/JVM *(KMP-ready)* | Identity, CID generation, signing, node lifecycle, protocols, and feed logic. |
| **`bootstrap`** | Kotlin/JVM | Infrastructure node: Rendezvous, Relay, shallow ring memory, and optional indexing. |
| **`android`** | Kotlin + Jetpack Compose | User interface with full RTL (Arabic) support: Timeline, Compose, Profile, Settings. |

### The Three Functional Layers

```
┌────────────────────────────────────────────────────────────────────────┐
│                        Data Ownership Layer                            │
│                 User Device: Keystore + Local Cache                    │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│                            Peer Layer                                  │
│                 GossipSub + Block Exchange Query                       │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │
┌──────────────────────────────────▼─────────────────────────────────────┐
│                          Bootstrap Layer                               │
│            Rendezvous + Circuit Relay + Ring Memory                    │
└────────────────────────────────────────────────────────────────────────┘

```

---

## 4. Cryptography, Identity & Content Addressing

### Cryptographic Stack

* **Identity Keys:** Ed25519 generated in Android Keystore (non-exportable) + encrypted backup.
* **Peer ID:** Self-computed `multihash(identity)` of the Protobuf-encoded public key.
* **Signatures:** Ed25519 calculated over **Canonical JSON** (alphabetical keys, UTF-8, strict integer formatting).
* **Direct Verification:** Public key is extracted directly from the Peer ID — zero lookup required.
* **E2EE (M5):** X25519 + ChaCha20-Poly1305 over the Noise layer for private messaging.

### Content Addressing (Zero-Dependency CID)

CIDs are implemented natively (~100 lines of code) supporting Base58btc, Base32 (RFC4648), and Multihash.

| Type | Format / Encoding | Usage |
| --- | --- | --- |
| **CIDv0** | `base58btc(0x12 0x20 sha256(data))` | Post identifiers (Canonical JSON hashes) |
| **CIDv1** | `raw base32(0x01 0x55 0x12 0x20 sha256(data))` | Media assets and block payloads |

---

## 5. Networking & Discovery (jvm-libp2p)

### Protocol Stack

* **Transport:** TCP
* **Encryption:** Noise (XX handshake) — mandatory across all connections
* **Multiplexing:** mplex
* **PubSub:** GossipSub v1.x
* **Auxiliary:** Identify, Ping, mDNS (Local Wi-Fi discovery)
* **Relay:** Circuit Relay via Bootstrap nodes for NAT traversal

### Topics & Discovery

```
/nodal/u/{peer_id}   --> User Topic (Following = Subscribing)
/nodal/feed/1.0.0    --> Global Discovery Feed

```

> **Known Constraints:**
> 1. No Kademlia DHT in `jvm-libp2p`: Peer discovery relies on Bootstrap, mDNS, manual connections, and inline post identities.
> 2. WebRTC is disabled for M1–M4 (Circuit Relay is used for NAT peers). WebRTC is targeted for M6.
> 
> 

---

## 6. Protocols & Timeline Pipeline

### Custom Network Protocols

All custom protocols use uniform length-prefixed Canonical JSON framing (`4-byte big-endian length + UTF-8 payload`).

* `/nodal/blocks/1.0.0` — Block Exchange (CID Request $\rightarrow$ Payload/NOT_FOUND response).
* `/nodal/sync/1.0.0` — Delta pull since cursor `{ topics[], since }`.
* `/nodal/search/1.0.0` — TTL-bounded distributed graph query.
* `/nodal/dm/1.0.0` — E2EE direct messaging.

### Push + Pull Pipeline

```
          [ Local Index ] ◄── Merge & Dedupe ◄── [ Incoming Buffer ]
                                                        ▲
                                                        │
┌───────────────────────────────┐       ┌───────────────┴───────────────┐
│     Pull (Catch-Up/Sync)      │       │          Push (Live)          │
│   Sync requests to peers/     │       │   GossipSub subscription      │
│     bootstrap ring memory     │       │      to followed topics       │
└───────────────────────────────┘       └───────────────────────────────┘

```

1. **Ingest Validation:** Verify Ed25519 signature, check timestamp ($\pm 5\text{ min}$ window), and enforce strict 64 KB size limit.
2. **Merge & Dedupe:** Deduplicate by CID, sort chronologically, and cap memory timeline at 500 posts.

---

## 7. Data Specifications & Schema

| Type Key | Payload Parameters | Description |
| --- | --- | --- |
| `post` | `text`, `media[]` | Standard post content |
| `profile` | `text` (bio), `media[0]` (avatar) | Profile metadata published on user topic |
| `list` | `entries[]` | Signed curated user lists (Discovery mechanism) |
| `follow` | `ref` (Target Peer ID) | Explicit follow event |
| `like` | `ref` (Post CID) | Public engagement event |
| `block` | `entries[]` | Personal network-level blocklist |

---

## 8. Development Roadmap

```
[M1] Core Engine ──► [M2] Bootstrap Network ──► [M3] Android UI ──► [M4] Cache & Offline ──► [M5] E2EE & Lists ──► [M6] WebRTC & KMP

```

* **M1: Core Engine** — Keys, CIDs, signing, jvm-libp2p transport, basic sync, and two-node local verification.
* **M2: Bootstrap Integration** — Ring memory, relay, rendezvous, and internet WAN testing.
* **M3: Android Application** — Jetpack Compose UI with full RTL support.
* **M4: Offline Storage** — Room cache integration, offline history reading, Wi-Fi media policies.
* **M5: Privacy & Curation** — E2EE direct messages, communities, and custom signed lists.
* **M6: Platform Expansion** — WebRTC implementation, Kotlin Multiplatform (iOS/Web) port.

---

## 9. Architectural Trade-offs & Limitations

| Feature | Limitation | Design Trade-off / Mitigation |
| --- | --- | --- |
| **Search** | Network bubble search | TTL-bounded queries; no central indexing engine. |
| **Trending** | Localized trends | Aggregated counters from immediate peers instead of global ranking. |
| **Availability** | Replication dependent | Unreplicated offline content becomes unavailable until the author re-connects. |
| **Bootstrapping** | Initial discovery dependency | Pre-seeded open-source bootstrap addresses (replaceable anytime). |

---

## 10. Definition of Success

1. Real internet end-to-end verification across two independent devices without local relaying.
2. Timeline successfully renders cryptographically verified post signatures.
3. Network topology remains fully functional even if all default bootstrap nodes are taken down (given active peer alternatives).
4. Absolute absence of central points of failure, control, or administration.
