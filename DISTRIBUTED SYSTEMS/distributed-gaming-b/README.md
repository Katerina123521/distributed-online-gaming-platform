# Σύστημα Online Τυχερών Παιχνιδιών
### Κατανεμημένα Συστήματα - Παραδοτέο Α (2025-2026)

---

## Ομάδα Εργασίας
| Ονοματεπώνυμο | Αριθμός Μητρώου |
| :--- | :--- |
| **Οδυσσέας Ψυχογιός** | p3230232 |
| **Αικατερίνη Γιαλού** | p3230024 |
| **Σταύρος Ιωάννης Σκοπελίτης** | p3230191 |

---

## 1. Επισκόπηση Αρχιτεκτονικής

Υλοποιήσαμε μια **κατανεμημένη πλατφόρμα τυχερών παιχνιδιών** από το μηδέν σε **Java** με αποκλειστική χρήση **TCP Sockets** και αρχιτεκτονική **Master-Worker** με **MapReduce**.



**Βασικές Αρχές Υλοποίησης:**
- **Μνήμη:** Όλα τα δεδομένα στη RAM (ArrayList/HashMap, όχι βάσεις δεδομένων).
- **Συγχρονισμός:** Αποκλειστικά `synchronized`, `wait()` και `notifyAll()`. **Καθόλου** `java.util.concurrent`.
- **Δίκτυο:** Αποκλειστικά `java.net.Socket` και `java.net.ServerSocket`.
- **Ασφάλεια:** SHA-256 για επαλήθευση τυχαίων αριθμών από τον SRG.

---

## 2. Δομή Αρχείων

```
distributed-gaming-a-2/
├── src/
│   ├── master/       MasterServer.java        ← Κεντρικός συντονιστής
│   ├── worker/       WorkerServer.java         ← Αποθήκευση & επεξεργασία
│   ├── reducer/      ReducerServer.java        ← Συλλογή MapReduce αποτελεσμάτων
│   ├── randomgen/    RandomGeneratorServer.java ← SRG (Producer-Consumer)
│   ├── manager/      ManagerClient.java        ← Κονσόλα διαχειριστή
│   ├── player/       DummyPlayerClient.java    ← Κονσόλα παίκτη
│   ├── model/        Game.java, Bet.java, SearchFilter.java
│   └── common/       Request.java, Response.java, HashUtil.java, Config.java
├── games/
│   ├── starburst.json       ← LOW risk, $$ κατηγορία
│   ├── megawheel.json       ← HIGH risk, $$$ κατηγορία
│   ├── fortuna.json         ← MEDIUM risk, $ κατηγορία
│   ├── ancient_treasures.json ← MEDIUM risk, $$$ κατηγορία
│   └── lightning.json       ← HIGH risk, $$ κατηγορία
├── out/                     ← Μεταγλωττισμένα .class αρχεία
├── workers.conf             ← Λίστα Workers (IP:PORT ανά γραμμή)
├── config.conf              ← Ρυθμίσεις Master/Reducer/SRG
├── compile.sh               ← Script μεταγλώττισης
└── run.sh                   ← Script εκτέλεσης
```

---

## 3. Ρύθμιση για Κατανεμημένο Περιβάλλον (3 Μηχανήματα)

### workers.conf
Επεξεργαστείτε το αρχείο `workers.conf` με τις **IP** των 3 μηχανημάτων:
```
# Παράδειγμα για 3 ξεχωριστά μηχανήματα:
192.168.1.10:6000
192.168.1.11:6000
192.168.1.12:6000
```

### config.conf
Επεξεργαστείτε το `config.conf` ανάλογα με το μηχάνημα (Master, Reducer, SRG):
```properties
master.host=192.168.1.1    # IP του Master μηχανήματος
master.port=5001

reducer.host=192.168.1.2   # IP του Reducer μηχανήματος
reducer.port=8001

srg.host=192.168.1.3       # IP του SRG μηχανήματος
srg.port=7001
```

---

## 4. Μεταγλώττιση και Εκτέλεση

### Βήμα 0: Μεταγλώττιση
```bash
./compile.sh
```

### Βήμα 1: Εκκίνηση Βοηθητικών Servers (σε ξεχωριστά τερματικά)
```bash
./run.sh reducer           # Reducer - port 8001
./run.sh srg               # Secured Random Generator - port 7001
```

### Βήμα 2: Εκκίνηση Workers (κάθε ένας σε ξεχωριστό μηχάνημα/τερματικό)
```bash
./run.sh worker 0 3 6000   # Worker-0: index=0, total=3, port=6000
./run.sh worker 1 3 6001   # Worker-1: index=1, total=3, port=6001
./run.sh worker 2 3 6002   # Worker-2: index=2, total=3, port=6002
```

### Βήμα 3: Εκκίνηση Master
```bash
./run.sh master            # Master - port 5001
```

### Βήμα 4: Εκτέλεση Clients
```bash
./run.sh manager           # Manager Console (φόρτωση παιχνιδιών)
./run.sh player            # Player Console (αναζήτηση & ποντάρισμα)
```

---

## 5. Λειτουργικές Λεπτομέρειες

### Hash-Based Routing
Ο Master επιλέγει Worker με: `index = |GameName.hashCode()| % numWorkers`

Παράδειγμα για 3 Workers:
- `"Starburst"` → Worker-1
- `"MegaWheelPro"` → Worker-0
- `"FortunaSlots"` → Worker-0

### MapReduce Flow (SEARCH/STATS)
1. **MAP:** Ο Master στέλνει `MAP_SEARCH:<reqId>:<N>` σε κάθε Worker async (N=αριθμός Workers).
2. Κάθε Worker φιλτράρει τα **δικά του** παιχνίδια (βάσει `ownerIndex = hash % N`) και τα στέλνει στον Reducer.
3. **REDUCE:** Ο Reducer συγκεντρώνει από όλους τους Workers. Όταν λάβει από και τους N, στέλνει το τελικό αποτέλεσμα στον Master.
4. Ο Master κάνει `notifyAll()` στο lock του αιτήματος. Το thread του client ξυπνά και παίρνει το αποτέλεσμα.

### Producer-Consumer (SRG)
- **Producer thread:** Γεννάει αριθμούς 0-999 με `Random`. Κάνει `wait()` αν το buffer (ArrayList) είναι γεμάτο (100 αριθμοί).
- **Consumer (Worker):** Ζητά αριθμό μέσω TCP. Κάνει `wait()` αν το buffer είναι άδειο.
- Κάθε παιχνίδι έχει το δικό του buffer (ανά HashKey).

### Bet Categories (MinBet)
| MinBet | Κατηγορία |
|--------|-----------|
| ≥ 5.0  | $$$       |
| ≥ 1.0  | $$        |
| < 1.0  | $         |

### Risk Arrays & Jackpot
| Risk   | Array                                        | Jackpot |
|--------|----------------------------------------------|---------|
| LOW    | [0.0, 0.0, 0.0, 0.1, 0.5, 1.0, 1.1, 1.3, 2.0, 2.5] | 10× |
| MEDIUM | [0.0, 0.0, 0.0, 0.0, 0.0, 0.5, 1.0, 1.5, 2.5, 3.5] | 20× |
| HIGH   | [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.0, 6.5] | 40× |

`rand % 100 == 0` → Jackpot | αλλιώς: `multiplier[rand % 10]`

---

