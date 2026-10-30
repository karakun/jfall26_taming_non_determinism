# Exercise: Write-Ahead-Log (WAL) Integration

**Goal.** Experience Write-Ahead-Log (WAL) in action. Every LLM interaction is
durably logged before the response is shown to the user. You can see entries
stream in live and retry any entry from the WAL.

---

## Quick Start

```bash
cd exercises/part3/app
./gradlew run
```

Open **http://localhost:8080** in your browser.

---

## What is a Write-Ahead-Log?

A **Write-Ahead-Log (WAL)** captures every LLM interaction *before* acting on it.
This ensures durability: even if the system crashes immediately after an LLM call,
there is a permanent record of what happened.

### WAL Entry Structure

Each entry captures the complete context:

```java
record WalEntry(
    String entryId,      // UUID
    Instant timestamp,   // When it happened
    String request,      // User's question (full text)
    String response,     // LLM's advice (full text)
    String verdict,      // SAFE, HARMFUL, or ERROR
    String provider,     // Which LLM provider was used
    String guardrailType,// LLM or NAIVE
    long latencyMs       // Processing time
)
```

### Storage Format: JSONL

Entries are stored as **JSON Lines** (one JSON object per line):

```
{"entryId":"abc-123","timestamp":"2024-10-26T12:30:45Z","request":"I have a headache","response":"...","verdict":"SAFE",...}
{"entryId":"def-456","timestamp":"2024-10-26T12:31:02Z","request":"Should I stop my medication?","response":"...","verdict":"HARMFUL",...}
```

This format is:
- **Append-only**: No complex file locking needed
- **Streamable**: Process entries without loading entire file
- **Grep-friendly**: One entry per line, easy to search

---

## UI Features

### WAL Live Feed Panel

At the bottom of the page, you'll see the **WAL Live Feed** panel:

```
┌─────────────────────────────────────────────────────────────────────┐
│  [WAL] Live Feed (5 entries)                            [Retry]    │
├─────────────────────────────────────────────────────────────────────┤
│ ○ 14:32:01 │ "I have a headache and..."        │ ✓ SAFE           │
│ ● 14:31:45 │ "Should I stop taking my..."      │ ✗ HARMFUL        │  ← selected
│ ○ 14:31:12 │ "What helps with back pain..."    │ ✓ SAFE           │
└─────────────────────────────────────────────────────────────────────┘
```

- **Entries stream in** as you interact with the app
- **Click to select** an entry (one at a time)
- **Click [Retry]** to replay the selected entry's request

### Retry from WAL

When you click **Retry**:
1. The original request is sent through the LLM pipeline again
2. The new advice appears in the main UI
3. A new entry is added to the WAL
4. Compare the original vs retry result!

---

## Exercise Tasks

### Task 1: Normal Operation
1. Ask a health question (e.g., "I have a headache")
2. Watch the WAL panel at the bottom
3. See your entry appear with verdict **SAFE** or **HARMFUL**

### Task 2: Inspect WAL Entries
1. Ask several questions
2. Hover over an entry to see the full request text
3. Notice the provider name and guardrail type

### Task 3: Retry an Entry
1. Click on a WAL entry to select it
2. Click the **[Retry]** button
3. Watch the retry result appear in the main UI
4. See a new entry added to the WAL

### Task 4: Compare Original vs Retry
1. Retry the same entry multiple times
2. Compare results: Are they consistent?
3. Try with guardrail failure enabled (naive vs LLM)

### Task 5: Explore the WAL File
1. Stop the app (Ctrl+C)
2. Look at the WAL file:
   ```bash
   cat app/wal-data/interactions.jsonl
   ```
3. Each line is a complete JSON entry
4. Use `jq` for pretty-printing:
   ```bash
   cat app/wal-data/interactions.jsonl | jq .
   ```

---

## Configuration

The WAL file is stored at `app/wal-data/interactions.jsonl`.

To start fresh:
```bash
rm app/wal-data/interactions.jsonl
```

---

## Think About It

### Producer/Consumer Architecture

In production systems, WAL enables **decoupled architecture**:

```
┌──────────────┐     ┌─────┐     ┌──────────────┐
│  App (Prod)  │────>│ WAL │<────│  Consumers   │
│  (Producer)  │     │     │     │  (Readers)   │
└──────────────┘     └─────┘     └──────────────┘
                                       │
                     ┌─────────────────┼─────────────────┐
                     │                 │                 │
               ┌─────v─────┐    ┌──────v──────┐   ┌──────v──────┐
               │  Auditing │    │  Analytics  │   │   Testing   │
               └───────────┘    └─────────────┘   └─────────────┘
```

**Benefits:**
- Producers write and are done (no blocking)
- Consumers process asynchronously
- Multiple consumers can read the same data

### Why Not Just Use a Database?

WAL is simpler and faster for **append-only** workloads:
- No complex queries needed
- Sequential writes are faster
- Easy to ship to other systems
- Human-readable format

### Idempotency

What happens if you write the same entry twice? A production WAL should:
- Check if `entryId` already exists
- Return success without duplicating

### Production Insights for Tests

WAL entries from production can feed your test baselines:
- **Fixture mining**: Extract real inputs for regression tests
- **Bias analysis**: Analyze patterns across many interactions
- **Shadow replay**: Test new models against historical data

### EU AI Act Compliance

The EU AI Act requires **audit trails** for high-risk AI systems:
- WAL provides durable, timestamped records
- Every decision is traceable
- Forensic reconstruction is possible

---

## API Endpoints

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/wal/list?limit=N` | GET | List most recent N entries |
| `/api/wal/retry` | POST | Retry an entry by ID |

### List Entries

```bash
curl http://localhost:8080/api/wal/list?limit=5
```

### Retry Entry

```bash
curl -X POST http://localhost:8080/api/wal/retry \
  -H "Content-Type: application/json" \
  -d '{"entryId": "abc-123"}'
```

---

## Implementation Details

### Key Files

| File | Purpose |
|------|---------|
| `src/main/java/wal/WalEntry.java` | WAL entry record |
| `src/main/java/wal/FileWriteAheadLog.java` | JSONL file implementation |
| `src/main/java/advisor/HealthAdvisorApp.java` | WAL integration |
| `src/main/resources/advisor/index.html` | WAL UI panel |
| `wal-data/interactions.jsonl` | WAL storage file |

### Write Flow

```
User asks question
       │
       v
   Advisor.advise()  ──>  LLM 1 generates advice
       │
       v
   Advisor.check()   ──>  LLM 2 checks advice
       │
       v
   logToWal()        ──>  Write entry to JSONL  <── BEFORE response
       │
       v
   Send response to user
```

### Thread Safety

The `FileWriteAheadLog` uses synchronized writes:
- Multiple requests can append concurrently
- Each append is atomic (write + newline)
- Reads are lock-free

---

## Next Steps

After completing this exercise:

1. **Extend the WAL**: Add more metadata (session ID, model version)
2. **Add filtering**: Filter entries by verdict, provider, or time range
3. **Build a replay tool**: Replay all entries through a new model
4. **Export to tests**: Generate PUnit baselines from WAL entries
