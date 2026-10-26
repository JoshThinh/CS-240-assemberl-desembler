# CS 240 – Assignment 5: Assembler + Disassembler

This repo has my work for the assembler/disassembler project. I took my MIPS FizzBuzz program, wrote an assembler in Java that turns it into machine code, and a disassembler that turns the machine code back into MIPS.

## What's in here

| File | What it is |
|---|---|
| `fizzbuzz.asm` | My original FizzBuzz program |
| `Assembler.java` | The assembler (started from the in-class code) |
| `fizzbuzz.mhc-ascii` | Machine code written as readable 0s and 1s |
| `fizzbuzz.mhc-bin` | The actual machine code bytes |
| `Disassembler.java` | The disassembler |
| `fizzbuzz_reconstructed.asm` | FizzBuzz rebuilt from the machine code |
| `fizzbuzz_reconstructed.mhc-bin` / `.mhc-ascii` | The reconstructed program assembled again (same bytes as the original) |
| `fizzbuzz_bin_hexdump.txt` | Hex dump of `fizzbuzz.mhc-bin`, since you can't open that file as text |
| `assemble_output.txt`, `error_tests.txt` | Assembler test evidence |
| `disassembler_test_output.txt` | Disassembler test evidence |

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

### How to run it
`Disassembler.java` uses some code from `Assembler.java`, so compile both together:

```
javac Assembler.java Disassembler.java
java Disassembler fizzbuzz.mhc-bin
```

That writes `fizzbuzz_reconstructed.asm`. It works with the text version too:

```
java Disassembler fizzbuzz.mhc-ascii
```

Both give the same `.asm` file (just the comment at the top is different).

### How it works
1. Reads the file. For mhc-ascii it skips the `#` lines and turns every 8 zeros and ones back into a byte, so after this step both formats are the same.
2. Reads the 20-byte header to find out where the instructions end and the data starts.
3. Decodes every 4-byte instruction by looking at the opcode (and the funct for R-type).
4. Makes a label for everything a branch or jump goes to (`L_<address>`), and calls the first instruction `main`.
5. Turns the data bytes back into `.asciiz` strings.

The original comments, label names, and pseudoinstructions don't come back, which the assignment says is fine. So `li $t1, 1` comes back as `addiu $t1, $zero, 1`, `la $a0, fizz` comes back as `lui` + `ori`, and `rem` comes back as `addiu $at` + `div` + `mfhi`. I kept them as real instructions on purpose so reassembling gives the exact same bytes.

### Checking that it matches
The disassembler checks itself. After writing the `.asm`, it runs it back through the assembler and compares the bytes:

```
Verified: reassembling fizzbuzz_reconstructed.asm gives the same 210 bytes as fizzbuzz.mhc-bin.
```

You can also check by hand:

```
java Assembler fizzbuzz_reconstructed.asm
cmp fizzbuzz.mhc-bin fizzbuzz_reconstructed.mhc-bin && echo "same bytes"
```

That prints `same bytes`, so the reconstructed program is identical to the original. Since it's the exact same machine code, it prints the same FizzBuzz output. I also ran it in a simulator and got FizzBuzz 1–100. If you want to run it in MARS, turn delayed branching off (Settings → Delayed branching).

### Errors
Bad input gives an error and no output file. For example:

```
Disassembly failed for bad_char.mhc-ascii: mhc-ascii line 12 has an invalid character '2' (only 0, 1, spaces, and # comment lines are allowed)
```

It also catches files that aren't from my assembler (no `MHC1` header), files that got cut off, instructions it doesn't support, and missing files. All of these are in `disassembler_test_output.txt`.

---

## Sources
- The assembler is built off the in-class assembler code from the CS 240 professor.
- I used Claude (Anthropic's AI) to help add the file reading and writing, the output file format, the error messages, and the disassembler, and to check my work.
