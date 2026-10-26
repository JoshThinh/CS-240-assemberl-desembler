import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.TreeMap;

/*
 * MIPS disassembler for CS 240 Assignment 5, Part 4. Java 8+.
 *
 * Reads either output format from Assembler.java (.mhc-bin or .mhc-ascii)
 * and writes a MIPS assembly file that rebuilds the same program.
 *
 * Build and run (Assembler.java must be in the same folder):
 *   javac Assembler.java Disassembler.java
 *   java Disassembler fizzbuzz.mhc-bin                -> fizzbuzz_reconstructed.asm
 *   java Disassembler fizzbuzz.mhc-ascii              -> fizzbuzz_reconstructed.asm
 *   java Disassembler fizzbuzz.mhc-bin out.asm        -> out.asm
 *
 * After writing the .asm file, the disassembler runs it back through the
 * assembler and checks that the bytes match the input file exactly.
 *
 * How it works:
 * 1. Load the bytes. For .mhc-ascii, skip '#' comment lines and turn every
 *    8 '0'/'1' characters into one byte. .mhc-bin is read as-is.
 * 2. Read the 20-byte MHC1 header (magic, text start, text size, data start,
 *    data size) to find where the instructions end and the data begins.
 * 3. Decode each 4-byte big-endian .text word into a real MIPS instruction.
 * 4. Give every branch/jump target a label (L_<address>), with main at 0x00400000.
 * 5. Turn the .data bytes back into .asciiz strings, labeled str_<address>.
 *
 * Only real instructions are written (no pseudoinstructions), so assembling
 * the output gives the exact same words. For example, li $t1, 1 comes back as
 * addiu $t1, $zero, 1, and la $a0, fizz comes back as lui + ori.
 * Original comments, label names, and pseudoinstruction spellings are not recovered.
 *
 * Supported instructions (the same set as Assembler.java):
 *   addi, addiu, addu, ori, lui, div, mfhi, sll, beq, bne, j, jal, jr, lw, sw, syscall
 */
public class Disassembler {
    static final String[] REG = {
        "$zero", "$at", "$v0", "$v1", "$a0", "$a1", "$a2", "$a3",
        "$t0", "$t1", "$t2", "$t3", "$t4", "$t5", "$t6", "$t7",
        "$s0", "$s1", "$s2", "$s3", "$s4", "$s5", "$s6", "$s7",
        "$t8", "$t9", "$k0", "$k1", "$gp", "$sp", "$fp", "$ra"
    };

    // Filled in from the header.
    int textBase, textSize, dataBase, dataSize;
    byte[] text, data;
    // address -> label name, sorted by address
    final TreeMap<Integer, String> labels = new TreeMap<>();

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.out.println("Usage: java Disassembler <input.mhc-bin | input.mhc-ascii> [output.asm]");
            System.out.println("Example: java Disassembler fizzbuzz.mhc-bin");
            System.out.println("  writes fizzbuzz_reconstructed.asm");
            System.exit(1);
        }
        Path input = Paths.get(args[0]);
        String outPath = args.length == 2 ? args[1] : defaultOutput(input);

        byte[] raw;
        try {
            raw = Files.readAllBytes(input);
        } catch (NoSuchFileException e) {
            System.out.println("Error: input file not found: " + args[0]);
            System.exit(1);
            return;
        } catch (IOException e) {
            System.out.println("Error: could not read " + args[0] + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        String asm;
        byte[] bytes;
        String format;
        try {
            boolean ascii = isAsciiFormat(input, raw);
            format = ascii ? "mhc-ascii" : "mhc-bin";
            bytes = ascii ? asciiToBytes(new String(raw, StandardCharsets.US_ASCII)) : raw;
            Disassembler d = new Disassembler();
            d.readHeader(bytes);
            asm = d.disassemble(input.getFileName().toString(), format);
        } catch (IllegalArgumentException e) {
            System.out.println("Disassembly failed for " + args[0] + ": " + e.getMessage());
            System.out.println("No output file was written.");
            System.exit(1);
            return;
        }

        try {
            Files.write(Paths.get(outPath), asm.getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            System.out.println("Error: could not write " + outPath + ": " + e.getMessage());
            System.exit(1);
            return;
        }
        System.out.println("Read " + args[0] + " as " + format + " (" + bytes.length + " bytes).");
        System.out.println("Wrote " + outPath);

        // Verification: reassemble the output and compare byte for byte.
        try {
            byte[] again = Assembler.container(Assembler.assemble(asm));
            if (Arrays.equals(again, bytes)) {
                System.out.println("Verified: reassembling " + outPath
                    + " gives the same " + bytes.length + " bytes as " + args[0] + ".");
            } else {
                System.out.println("WARNING: reassembled bytes do NOT match " + args[0] + ".");
                System.exit(2);
            }
        } catch (IllegalArgumentException e) {
            System.out.println("WARNING: reconstructed file did not reassemble: " + e.getMessage());
            System.exit(2);
        }
    }

    // fizzbuzz.mhc-bin -> fizzbuzz_reconstructed.asm, in the same folder.
    static String defaultOutput(Path input) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return input.resolveSibling(name + "_reconstructed.asm").toString();
    }

    // Use the file extension; if it is neither, look at the contents.
    static boolean isAsciiFormat(Path input, byte[] raw) {
        String name = input.getFileName().toString().toLowerCase();
        if (name.endsWith(".mhc-ascii")) return true;
        if (name.endsWith(".mhc-bin")) return false;
        if (raw.length >= 4 && raw[0] == 'M' && raw[1] == 'H' && raw[2] == 'C' && raw[3] == '1') {
            return false;
        }
        return true;
    }

    // Skip '#' lines, ignore whitespace, and pack each 8 bit characters into a byte.
    static byte[] asciiToBytes(String textFile) {
        StringBuilder bits = new StringBuilder();
        String[] lines = textFile.split("\\r\\n|\\r|\\n");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            for (char c : line.toCharArray()) {
                if (c == '0' || c == '1') bits.append(c);
                else if (!Character.isWhitespace(c)) {
                    fail("mhc-ascii line " + (i + 1) + " has an invalid character '" + c
                         + "' (only 0, 1, spaces, and # comment lines are allowed)");
                }
            }
        }
        if (bits.length() % 8 != 0) {
            fail("mhc-ascii has " + bits.length() + " bits, which is not a whole number of bytes");
        }
        byte[] out = new byte[bits.length() / 8];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(bits.substring(i * 8, i * 8 + 8), 2);
        }
        return out;
    }

    void readHeader(byte[] b) {
        if (b.length < Assembler.HEADER_SIZE) {
            fail("file is only " + b.length + " bytes; the MHC1 header needs 20");
        }
        if (b[0] != 'M' || b[1] != 'H' || b[2] != 'C' || b[3] != '1') {
            fail("missing \"MHC1\" magic at the start; this is not an assembler output file");
        }
        textBase = word(b, 4);
        textSize = word(b, 8);
        dataBase = word(b, 12);
        dataSize = word(b, 16);
        if (textBase != Assembler.TEXT_BASE || dataBase != Assembler.DATA_BASE) {
            fail("unexpected start addresses (text 0x" + Assembler.hex(textBase)
                 + ", data 0x" + Assembler.hex(dataBase) + ")");
        }
        if (textSize <= 0 || textSize % 4 != 0) {
            fail(".text size " + textSize + " must be a positive multiple of 4");
        }
        long expected = (long) Assembler.HEADER_SIZE + (textSize & 0xFFFFFFFFL) + (dataSize & 0xFFFFFFFFL);
        if (dataSize < 0 || expected != b.length) {
            fail("header says " + expected + " bytes total, but the file has " + b.length);
        }
        text = Arrays.copyOfRange(b, 20, 20 + textSize);
        data = Arrays.copyOfRange(b, 20 + textSize, b.length);
    }

    String disassemble(String inputName, String format) {
        int count = textSize / 4;
        int[] words = new int[count];
        for (int i = 0; i < count; i++) words[i] = word(text, i * 4);

        // Pass 1: find every branch/jump target so it can get a label.
        labels.put(textBase, "main");
        for (int i = 0; i < count; i++) {
            int addr = textBase + i * 4;
            int target = targetOf(words[i], addr);
            if (target != -1 && !labels.containsKey(target)) {
                labels.put(target, "L_" + Assembler.hex(target));
            }
        }

        StringBuilder out = new StringBuilder();
        out.append("# Reconstructed by Disassembler.java from ").append(inputName)
           .append(" (").append(format).append(")\n");
        out.append("# Comments show each word's address and machine code.\n\n");

        // .data: rebuild the strings.
        out.append(".data\n");
        appendData(out);

        // .text: pass 2, decode each word.
        out.append("\n.text\n.globl main\n");
        for (int i = 0; i < count; i++) {
            int addr = textBase + i * 4;
            String label = labels.get(addr);
            if (label != null) out.append(label).append(":\n");
            String ins;
            try {
                ins = decode(words[i], addr);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("word at 0x" + Assembler.hex(addr)
                    + " (0x" + Assembler.hex(words[i]) + "): " + e.getMessage());
            }
            String line = "    " + ins;
            out.append(String.format("%-36s# 0x%s: 0x%s%n", line,
                       Assembler.hex(addr), Assembler.hex(words[i])).replace("\r", ""));
        }
        return out.toString();
    }

    // Split .data on 0 bytes; each piece becomes one .asciiz string.
    void appendData(StringBuilder out) {
        if (dataSize == 0) return;
        if (data[dataSize - 1] != 0) {
            fail(".data does not end with a 0 byte, so it cannot be written as .asciiz strings");
        }
        int start = 0;
        for (int i = 0; i < dataSize; i++) {
            if (data[i] != 0) continue;
            StringBuilder s = new StringBuilder();
            for (int j = start; j < i; j++) {
                int c = data[j] & 255;
                if (c == '\n') s.append("\\n");
                else if (c == '\t') s.append("\\t");
                else if (c == '\r') s.append("\\r");
                else if (c == '"') s.append("\\\"");
                else if (c == '\\') s.append("\\\\");
                else if (c >= 32 && c <= 126) s.append((char) c);
                else fail(".data byte 0x" + Integer.toHexString(c) + " at 0x"
                          + Assembler.hex(dataBase + j) + " is not printable ASCII");
            }
            out.append("str_").append(Assembler.hex(dataBase + start))
               .append(": .asciiz \"").append(s).append("\"\n");
            start = i + 1;
        }
    }

    // Branch/jump target address, or -1 if the word is not a branch or jump.
    int targetOf(int w, int addr) {
        int op = w >>> 26;
        if (op == 4 || op == 5) return addr + 4 + ((short) (w & 0xFFFF)) * 4;
        if (op == 2 || op == 3) return ((addr + 4) & 0xF0000000) | ((w & 0x03FFFFFF) << 2);
        return -1;
    }

    String decode(int w, int addr) {
        int op = w >>> 26;
        int rs = (w >>> 21) & 31, rt = (w >>> 16) & 31, rd = (w >>> 11) & 31;
        int shamt = (w >>> 6) & 31, funct = w & 63;
        int uimm = w & 0xFFFF;
        int simm = (short) uimm;

        switch (op) {
            case 0: // R-type: choose by funct
                switch (funct) {
                    case 0x21:
                        zero(shamt, "shamt");
                        return "addu " + REG[rd] + ", " + REG[rs] + ", " + REG[rt];
                    case 0x1A:
                        zero(rd, "rd"); zero(shamt, "shamt");
                        return "div " + REG[rs] + ", " + REG[rt];
                    case 0x10:
                        zero(rs, "rs"); zero(rt, "rt"); zero(shamt, "shamt");
                        return "mfhi " + REG[rd];
                    case 0x00:
                        zero(rs, "rs");
                        return "sll " + REG[rd] + ", " + REG[rt] + ", " + shamt;
                    case 0x08:
                        zero(rt, "rt"); zero(rd, "rd"); zero(shamt, "shamt");
                        return "jr " + REG[rs];
                    case 0x0C:
                        if (w != 0x0000000C) fail("syscall with nonzero fields");
                        return "syscall";
                    default:
                        fail("unsupported R-type funct " + funct);
                }
            case 8:  return "addi " + REG[rt] + ", " + REG[rs] + ", " + simm;
            case 9:  return "addiu " + REG[rt] + ", " + REG[rs] + ", " + simm;
            case 13: return "ori " + REG[rt] + ", " + REG[rs] + ", " + uimm;
            case 15:
                zero(rs, "rs");
                return "lui " + REG[rt] + ", " + uimm;
            case 4:
            case 5:
                return (op == 4 ? "beq " : "bne ") + REG[rs] + ", " + REG[rt] + ", "
                       + textLabel(targetOf(w, addr));
            case 2:
            case 3:
                return (op == 2 ? "j " : "jal ") + textLabel(targetOf(w, addr));
            case 35:
            case 43:
                return (op == 35 ? "lw " : "sw ") + REG[rt] + ", " + simm + "(" + REG[rs] + ")";
            default:
                fail("unsupported opcode " + op);
                return null;
        }
    }

    String textLabel(int target) {
        if (target < textBase || target >= textBase + textSize) {
            fail("branch/jump target 0x" + Assembler.hex(target) + " is outside .text");
        }
        return labels.get(target);
    }

    static void zero(int field, String name) {
        if (field != 0) fail("field " + name + " should be 0 but is " + field);
    }

    static int word(byte[] b, int i) {
        return ((b[i] & 255) << 24) | ((b[i + 1] & 255) << 16) | ((b[i + 2] & 255) << 8) | (b[i + 3] & 255);
    }

    static void fail(String message) {
        throw new IllegalArgumentException(message);
    }
}
