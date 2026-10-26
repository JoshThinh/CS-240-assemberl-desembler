# CS 240 – Assignment 5: Assembler + Disassembler

This repo has my work for the assembler/disassembler project. I took my MIPS FizzBuzz program, wrote an assembler in Java that turns it into machine code, and a disassembler that turns the machine code back into MIPS.

## What's in here

| File | What it is |
|---|---|
| `fizzbuzz.asm` | My original FizzBuzz program |
| `Assembler.java` | The assembler (started from the in-class code) |
| `fizzbuzz.mhc-ascii` | Machine code written as readable 0s and 1s |
| `fizzbuzz.mhc-bin` | The actual machine code bytes |
| `Disassembler.java` | The disassembler (Part 4) |
| reconstructed `.asm` | FizzBuzz rebuilt from the machine code (Part 4) |

---

## Part 3: The assembler

### How to run it
You need Java (JDK 8 or newer). Run these in the terminal from this folder:

```
javac Assembler.java
java Assembler fizzbuzz.asm
```

That creates `fizzbuzz.mhc-ascii` and `fizzbuzz.mhc-bin` and prints out the full listing.

Heads up: running it with the VS Code play button won't work, because it doesn't pass in the `.asm` file. Use the terminal.

### What it supports
Everything my FizzBuzz uses:
- **Instructions:** addi, addiu, addu, ori, lui, div, mfhi, sll, beq, bne, j, jal, jr, lw, sw, syscall
- **Pseudoinstructions:** li, la, move, rem, nop (these get expanded into real instructions)
- **Directives:** .data, .text, .asciiz, .globl
- **Labels** and `#` comments

### Addresses and byte order
- `.text` (instructions) starts at `0x00400000`
- `.data` (strings) starts at `0x10010000`
- Everything is **big-endian**, so the most significant byte comes first

### File layout
Both output files start with a small header, so you can tell where the instructions stop and the data starts:

| Bytes | What it is |
|---|---|
| 0–3 | `MHC1` (marks the file type) |
| 4–7 | where .text starts |
| 8–11 | how many bytes of .text (180 = 45 instructions) |
| 12–15 | where .data starts |
| 16–19 | how many bytes of .data (10 = "Fizz" and "Buzz") |
| after that | the instructions, then the data |

`mhc-bin` is just those bytes (210 bytes total). `mhc-ascii` is the same bytes written as 0s and 1s, 32 per line. Lines starting with `#` are just labels for the sections.

### Errors
If something's wrong in the `.asm` file, it tells you the line number, the line itself, and what's wrong, and it doesn't write any output files. For example:

```
Assembly failed in bad.asm: Line 3 (mul $t0, $t1, $t2): Unsupported instruction: mul
```

It also catches unknown registers, labels that don't exist, numbers that are too big, unsupported directives, and missing files.

### Test evidence
- Assembling FizzBuzz: 45 instructions, 180 bytes of .text, 10 bytes of .data
- I ran the machine code through a simulator, and it printed FizzBuzz 1–100 correctly
- Converting mhc-ascii back to bytes gives exactly the same bytes as mhc-bin
- Bad inputs (wrong instruction, bad register, missing label, number too big, bad directive, missing file) all give an error message

---

## Part 4: The disassembler

*(coming next)*

---

## Sources
- The assembler is built off the in-class assembler code from the CS 240 professor.
- I used Claude (Anthropic's AI) to help add the file reading and writing, the output file format, and the error messages, and to check my work.
