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

## Part 1: Assembling by hand

**`addi $t0, $zero, 3`**: I-type (6 | 5 | 5 | 16 bits)
- opcode (addi) = `001000`
- rs = `$zero` = 0 → `00000`
- rt = `$t0` = 8 → `01000`
- immediate = 3 → `0000000000000011`

Result: `001000 00000 01000 0000000000000011`

**`addi $t1, $zero, 5`**: I-type
- opcode (addi) = `001000`
- rs = `$zero` = 0 → `00000`
- rt = `$t1` = 9 → `01001`
- immediate = 5 → `0000000000000101`

Result: `001000 00000 01001 0000000000000101`

**`add $t2, $t0, $t1`**: R-type (6 | 5 | 5 | 5 | 5 | 6 bits)
- opcode = `000000` (always 0 for R-type)
- rs = `$t0` = 8 → `01000`
- rt = `$t1` = 9 → `01001`
- rd = `$t2` = 10 → `01010`
- shamt = `00000`
- funct (add) = `100000`

Result: `000000 01000 01001 01010 00000 100000`

**`sub $t3, $t1, $t0`**: R-type
- opcode = `000000`
- rs = `$t1` = 9 → `01001`
- rt = `$t0` = 8 → `01000`
- rd = `$t3` = 11 → `01011`
- shamt = `00000`
- funct (sub) = `100010`

Result: `000000 01001 01000 01011 00000 100010`

---

## Part 2: Bytes and hex

Each 32-bit instruction is split into four 8-bit bytes, and each byte becomes 2 hex digits. The bytes are listed most significant first (big-endian).

| Instruction | Byte 1 | Byte 2 | Byte 3 | Byte 4 | Hex |
|---|---|---|---|---|---|
| `addi $t0, $zero, 3` | `00100000` | `00001000` | `00000000` | `00000011` | `20 08 00 03` |
| `addi $t1, $zero, 5` | `00100000` | `00001001` | `00000000` | `00000101` | `20 09 00 05` |
| `add $t2, $t0, $t1` | `00000001` | `00001001` | `01010000` | `00100000` | `01 09 50 20` |
| `sub $t3, $t1, $t0` | `00000001` | `00101000` | `01011000` | `00100010` | `01 28 58 22` |

**mhc-ascii vs mhc-bin:**
The two formats, mhc-ascii and mhc-bin, differ in how they store and display data. mhc-ascii is a text file that contains only the characters 0 and 1. Each one of these characters takes up one byte, so 32 bits turn into 32 bytes. mhc-bin is a binary file that stores the actual machine code bytes, so 8 bits are packed into 1 byte instead of each bit taking up a whole byte. One instruction is 4 bytes, which is 8 times smaller than the 32 bytes mhc-ascii uses. mhc-ascii is nice because you can actually read it, while mhc-bin is what the CPU uses, but you need a hex viewer to look at it.

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
