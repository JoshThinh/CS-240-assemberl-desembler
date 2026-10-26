import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * Toy MIPS assembler. Java 8+.
 * Based on the CS 240 in-class assembler; extended for Assignment 5 Part 3
 * to read a .asm file and write .mhc-bin and .mhc-ascii output files.
 *
 * Build and run:
 *   javac Assembler.java
 *   java Assembler fizzbuzz.asm               -> fizzbuzz.mhc-bin, fizzbuzz.mhc-ascii
 *   java Assembler fizzbuzz.asm out/fizzbuzz  -> out/fizzbuzz.mhc-bin, out/fizzbuzz.mhc-ascii
 * The output files are written next to the input file unless a base name is given.
 * A full binary and hex listing is also printed to the console as test evidence.
 *
 * Two passes: expand pseudoinstructions and record labels, then encode.
 * Fixed addresses: .text = 0x00400000, .data = 0x10010000.
 * Words use BIG-ENDIAN byte order (most significant byte first).
 *
 * MHC1 file layout (all header words are 32-bit big-endian):
 *   offset  0  4 bytes  magic "MHC1" (0x4D 0x48 0x43 0x31)
 *   offset  4  4 bytes  .text start address (0x00400000)
 *   offset  8  4 bytes  .text size in bytes (always a multiple of 4)
 *   offset 12  4 bytes  .data start address (0x10010000)
 *   offset 16  4 bytes  .data size in bytes (not padded)
 *   offset 20  .text bytes: one 4-byte big-endian word per instruction
 *   then       .data bytes: raw bytes in memory order
 * The header sizes are what let a disassembler separate instructions from data.
 *
 * fizzbuzz.mhc-bin   holds exactly the bytes above.
 * fizzbuzz.mhc-ascii holds the SAME bytes, each written as 8 '0'/'1' characters,
 *                    4 bytes (32 characters) per line. The final .data line may be
 *                    shorter. Lines starting with '#' are comments that label the
 *                    header, .text and .data sections; a reader must ignore them.
 *
 * Real instructions:
 *   addi, addiu, addu, ori, lui, div, mfhi, sll,
 *   beq, bne, j, jal, jr, lw, sw, syscall
 * Pseudoinstructions: li, la, move, rem, nop
 * Directives: .text, .data, .asciiz, .globl, .global
 *
 * Limitations:
 * - .asciiz accepts one ASCII string per directive.
 * - rem accepts a nonzero immediate divisor and uses reserved register $at.
 *   It omits MARS's extra runtime overflow checks.
 * - No automatic delay slots. The demo requires delayed branching OFF.
 *   Explicit nop instructions in user input are still assembled normally.
 * - Only the default .text and .data base addresses are supported.
 *
 * Errors: unsupported instructions/directives, unknown registers, undefined or
 * duplicate labels, bad operands, out-of-range values, and missing/unreadable
 * files are reported with the ASM line number and source text, and the program
 * exits with status 1 without writing output files.
 */
public class Assembler {
    static final int TEXT_BASE = 0x00400000;
    static final int DATA_BASE = 0x10010000;
    static final boolean RAW_CHARACTER_VIEW = false;

    private static final String[] REGISTER_NAMES = {
        "zero", "at", "v0", "v1", "a0", "a1", "a2", "a3",
        "t0", "t1", "t2", "t3", "t4", "t5", "t6", "t7",
        "s0", "s1", "s2", "s3", "s4", "s5", "s6", "s7",
        "t8", "t9", "k0", "k1", "gp", "sp", "fp", "ra"
    };

    static final byte[] MAGIC = {'M', 'H', 'C', '1'};
    static final int HEADER_SIZE = 20;

    static class Instruction {
        final int line, address;
        final String text, sourceText;
        Instruction(int line, int address, String text, String sourceText) {
            this.line = line;
            this.address = address;
            this.text = text;
            this.sourceText = sourceText;
        }
    }

    static class EncodedInstruction {
        final Instruction source;
        final int word;
        final String mips;
        final Map<String, Integer> labelLines;
        EncodedInstruction(Instruction source, int word, String mips,
                           Map<String, Integer> labelLines) {
            this.source = source;
            this.word = word;
            this.mips = mips;
            this.labelLines = labelLines;
        }
    }

    static class Result {
        final byte[] text, data;
        final List<EncodedInstruction> instructions;
        Result(byte[] text, byte[] data, List<EncodedInstruction> instructions) {
            this.text = text;
            this.data = data;
            this.instructions = instructions;
        }
    }

    private final Map<String, Integer> labels = new HashMap<>();
    private final Map<String, Integer> labelLines = new HashMap<>();
    private final Set<String> textLabels = new HashSet<>();
    private final List<String> globals = new ArrayList<>();
    private final List<Instruction> instructions = new ArrayList<>();
    private final List<Byte> data = new ArrayList<>();
    private boolean inData = false;
    private int pc = TEXT_BASE;

    public static void main(String[] args) {
        if (args.length < 1 || args.length > 2) {
            System.out.println("Usage: java Assembler <input.asm> [output-base-name]");
            System.out.println("Example: java Assembler fizzbuzz.asm");
            System.out.println("  writes fizzbuzz.mhc-bin and fizzbuzz.mhc-ascii");
            System.exit(1);
        }
        Path input = Paths.get(args[0]);
        String base = args.length == 2 ? args[1] : defaultBaseName(input);

        String source;
        try {
            source = new String(Files.readAllBytes(input), StandardCharsets.UTF_8);
        } catch (NoSuchFileException e) {
            System.out.println("Error: input file not found: " + args[0]);
            System.exit(1);
            return;
        } catch (IOException e) {
            System.out.println("Error: could not read " + args[0] + ": " + e.getMessage());
            System.exit(1);
            return;
        }

        Result result;
        try {
            result = assemble(source);
        } catch (IllegalArgumentException e) {
            System.out.println("Assembly failed in " + args[0] + ": " + e.getMessage());
            System.out.println("No output files were written.");
            System.exit(1);
            return;
        }

        String binPath = base + ".mhc-bin";
        String asciiPath = base + ".mhc-ascii";
        byte[] bin = container(result);
        try {
            Files.write(Paths.get(binPath), bin);
            Files.write(Paths.get(asciiPath),
                        asciiContainer(bin, result).getBytes(StandardCharsets.US_ASCII));
        } catch (IOException e) {
            System.out.println("Error: could not write output files: " + e.getMessage());
            System.exit(1);
            return;
        }

        System.out.println(renderView(result, 1));
        System.out.println(renderView(result, 2));
        System.out.println();
        System.out.println("Assembled " + args[0] + ": "
            + result.instructions.size() + " instructions (" + result.text.length
            + " bytes of .text), " + result.data.length + " bytes of .data.");
        System.out.println("Wrote " + binPath + " (" + bin.length + " bytes)");
        System.out.println("Wrote " + asciiPath);
    }

    // fizzbuzz.asm -> fizzbuzz, in the same folder as the input.
    static String defaultBaseName(Path input) {
        String name = input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) name = name.substring(0, dot);
        return input.resolveSibling(name).toString();
    }

    // Build the MHC1 container: 20-byte header, then .text, then .data.
    static byte[] container(Result result) {
        byte[] out = new byte[HEADER_SIZE + result.text.length + result.data.length];
        System.arraycopy(MAGIC, 0, out, 0, 4);
        putWord(out, 4, TEXT_BASE);
        putWord(out, 8, result.text.length);
        putWord(out, 12, DATA_BASE);
        putWord(out, 16, result.data.length);
        System.arraycopy(result.text, 0, out, HEADER_SIZE, result.text.length);
        System.arraycopy(result.data, 0, out, HEADER_SIZE + result.text.length,
                         result.data.length);
        return out;
    }

    // Store a 32-bit word in BIG-ENDIAN order.
    static void putWord(byte[] out, int offset, int word) {
        for (int i = 0; i < 4; i++) {
            out[offset + i] = (byte) (word >>> (24 - 8 * i));
        }
    }

    // The same bytes as the .mhc-bin file, written as readable '0'/'1' text.
    static String asciiContainer(byte[] bin, Result result) {
        StringBuilder out = new StringBuilder();
        out.append("# mhc-ascii (MHC1): same bytes as .mhc-bin, 8 bits per byte, big-endian\n");
        out.append("# Lines starting with # are comments. All other characters are 0, 1, or whitespace.\n");
        out.append("# header: magic \"MHC1\", text start, text size, data start, data size\n");
        appendBits(out, bin, 0, HEADER_SIZE);
        out.append("# .text @ 0x").append(hex(TEXT_BASE)).append(" - ")
           .append(result.text.length).append(" bytes, ")
           .append(result.instructions.size()).append(" instructions\n");
        appendBits(out, bin, HEADER_SIZE, result.text.length);
        out.append("# .data @ 0x").append(hex(DATA_BASE)).append(" - ")
           .append(result.data.length).append(" bytes\n");
        appendBits(out, bin, HEADER_SIZE + result.text.length, result.data.length);
        return out.toString();
    }

    // 4 bytes (32 bit characters) per line; a final partial line is allowed.
    private static void appendBits(StringBuilder out, byte[] bytes, int start, int length) {
        for (int i = 0; i < length; i++) {
            String bits = Integer.toBinaryString(bytes[start + i] & 255);
            out.append("00000000".substring(bits.length())).append(bits);
            if (i % 4 == 3 || i == length - 1) out.append('\n');
        }
    }

    static String renderView(Result result, int view) {
        StringBuilder output = new StringBuilder();
        if (view == 1) {
            appendLine(output, "=== 1. BINARY MACHINE CODE - 32 bits per word ===");
            appendBinaryInstructions(output, result);
            appendSection(output, result.data, ".data", DATA_BASE, 2);
        } else if (view == 2) {
            appendLine(output, "\n=== 2. HEX MACHINE CODE - 8 digits per word ===");
            appendSection(output, result.text, ".text", TEXT_BASE, 16);
            appendSection(output, result.data, ".data", DATA_BASE, 16);
        } else if (view == 3) {
            appendLine(output, RAW_CHARACTER_VIEW
                ? "\n=== 3. RAW CHARACTER VIEW - control characters may disrupt output ==="
                : "\n=== 3. ASCII VIEW - control/non-ASCII bytes escaped ===");
            appendSection(output, result.text, ".text", TEXT_BASE, 0);
            appendSection(output, result.data, ".data", DATA_BASE, 0);
        } else {
            fail("View must be 1, 2, or 3.");
        }
        // println supplies the last newline; preserve the listing byte for byte.
        return output.substring(0, output.length() - 1);
    }

    private static void appendLine(StringBuilder output, String line) {
        output.append(line).append('\n');
    }

    static Result assemble(String source) {
        Assembler assembler = new Assembler();
        assembler.firstPass(source);
        if (assembler.instructions.isEmpty()) fail("No instructions found.");
        for (String name : assembler.globals) assembler.label(name);

        // Pass 2: all labels now have final addresses.
        byte[] text = new byte[assembler.instructions.size() * 4];
        List<EncodedInstruction> encoded = new ArrayList<>();
        int index = 0;
        for (Instruction instruction : assembler.instructions) {
            try {
                int word = assembler.encode(instruction);
                encoded.add(new EncodedInstruction(instruction, word,
                            assembler.resolvedMips(instruction), assembler.labelLines));
                for (int shift = 24; shift >= 0; shift -= 8) {
                    text[index++] = (byte) (word >>> shift);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Line " + instruction.line + " (" + instruction.sourceText
                                                   + "): " + e.getMessage());
            }
        }
        byte[] bytes = new byte[assembler.data.size()];
        for (int i = 0; i < bytes.length; i++) bytes[i] = assembler.data.get(i);
        return new Result(text, bytes, encoded);
    }

    // Pass 1: expand pseudos BEFORE calculating following label addresses.
    private void firstPass(String source) {
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        String[] lines = source.split("\\r\\n|\\r|\\n");
        for (int i = 0; i < lines.length; i++) {
            try {
                String text = withoutComment(lines[i]).trim();
                while (true) {
                    int colon = text.indexOf(':');
                    if (colon < 0) break;
                    String name = text.substring(0, colon).trim();
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) break;
                    if (labels.containsKey(name)) fail("Duplicate label: " + name);
                    labels.put(name, inData ? DATA_BASE + data.size() : pc);
                    labelLines.put(name, i + 1);
                    if (!inData) textLabels.add(name);
                    text = text.substring(colon + 1).trim();
                }
                if (text.isEmpty()) continue;
                if (text.startsWith(".")) {
                    directive(text);
                } else {
                    if (inData) fail("Instructions must be in .text.");
                    expand(text, i + 1);
                }
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Line " + (i + 1) + " (" + lines[i].trim()
                                                   + "): " + e.getMessage());
            }
        }
    }

    private void directive(String text) {
        String[] p = text.split("\\s+", 2);
        String value = p.length == 2 ? p[1].trim() : "";
        switch (p[0]) {
            case ".text":
            case ".data":
                boolean nextData = p[0].equals(".data");
                int base = nextData ? DATA_BASE : TEXT_BASE;
                if (!value.isEmpty()) {
                    if (number(value) != base) fail("Only the default segment base is supported.");
                    if (nextData ? !data.isEmpty() : !instructions.isEmpty()) {
                        fail("Explicit segment address must precede its contents.");
                    }
                }
                inData = nextData;
                break;
            case ".globl":
            case ".global":
                if (!value.matches("[A-Za-z_][A-Za-z0-9_]*")) fail("Expected one global label.");
                globals.add(value);
                break;
            case ".asciiz":
                if (!inData) fail(".asciiz must be in .data.");
                String decoded = quoted(value, '"');
                for (int i = 0; i < decoded.length(); i++) data.add((byte) decoded.charAt(i));
                data.add((byte) 0);
                break;
            default:
                fail("Unsupported directive: " + p[0]);
        }
    }

    private void emit(String text, int line, String sourceText) {
        instructions.add(new Instruction(line, pc, text, sourceText));
        pc += 4;
    }

    private void loadImmediate(String register, int value, int line, String sourceText) {
        reg(register);
        if (value >= -32768 && value <= 32767) {
            emit("addiu " + register + ", $zero, " + value, line, sourceText);
        } else if (value >= 0 && value <= 65535) {
            emit("ori " + register + ", $zero, " + value, line, sourceText);
        } else {
            emit("lui " + register + ", " + (value >>> 16), line, sourceText);
            emit("ori " + register + ", " + register + ", " + (value & 65535), line, sourceText);
        }
    }

    private void expand(String text, int line) {
        String[] p = text.split("\\s+", 2);
        String op = lowerAscii(p[0]);
        String[] a = operands(p);
        switch (op) {
            case "li":
                count(a, 2);
                loadImmediate(a[0], number(a[1]), line, text);
                break;
            case "la":
                count(a, 2);
                reg(a[0]);
                if (!a[1].matches("[A-Za-z_][A-Za-z0-9_]*")) fail("la expects a label.");
                emit("lui " + a[0] + ", hi(" + a[1] + ")", line, text);
                emit("ori " + a[0] + ", " + a[0] + ", lo(" + a[1] + ")", line, text);
                break;
            case "move":
                count(a, 2);
                emit("addu " + a[0] + ", " + a[1] + ", $zero", line, text);
                break;
            case "rem":
                count(a, 3);
                if (reg(a[0]) == 1 || reg(a[1]) == 1) fail("rem reserves $at.");
                int divisor = number(a[2]);
                if (divisor == 0) fail("rem divisor cannot be zero.");
                loadImmediate("$at", divisor, line, text);
                emit("div " + a[1] + ", $at", line, text);
                emit("mfhi " + a[0], line, text);
                break;
            case "nop":
                count(a, 0);
                emit("sll $zero, $zero, 0", line, text);
                break;
            default:
                emit(text, line, text);
        }
    }

    private int encode(Instruction instruction) {
        String[] p = instruction.text.split("\\s+", 2);
        String op = lowerAscii(p[0]);
        String[] a = operands(p);
        switch (op) {
            case "addi":
            case "addiu":
            case "ori":
                count(a, 3);
                int immediate = value(a[2]);
                range(immediate, op.equals("ori") ? 0 : -32768,
                                 op.equals("ori") ? 65535 : 32767);
                return iType(op.equals("addi") ? 8 : op.equals("addiu") ? 9 : 13,
                             reg(a[1]), reg(a[0]), immediate);
            case "lui":
                count(a, 2);
                return iType(15, 0, reg(a[0]), range(value(a[1]), 0, 65535));
            case "addu":
                count(a, 3);
                return rType(reg(a[1]), reg(a[2]), reg(a[0]), 0, 0x21);
            case "div":
                count(a, 2);
                return rType(reg(a[0]), reg(a[1]), 0, 0, 0x1A);
            case "mfhi":
                count(a, 1);
                return rType(0, 0, reg(a[0]), 0, 0x10);
            case "sll":
                count(a, 3);
                return rType(0, reg(a[1]), reg(a[0]), range(number(a[2]), 0, 31), 0);
            case "beq":
            case "bne":
                count(a, 3);
                int offset = (codeLabel(a[2]) - (instruction.address + 4)) / 4;
                return iType(op.equals("beq") ? 4 : 5, reg(a[0]), reg(a[1]),
                             range(offset, -32768, 32767));
            case "j":
            case "jal":
                count(a, 1);
                int target = codeLabel(a[0]);
                if ((target & 0xF0000000) != ((instruction.address + 4) & 0xF0000000)) {
                    fail("Jump target is out of range.");
                }
                return ((op.equals("j") ? 2 : 3) << 26) | ((target >>> 2) & 0x03FFFFFF);
            case "jr":
                count(a, 1);
                return rType(reg(a[0]), 0, 0, 0, 8);
            case "lw":
            case "sw":
                count(a, 2);
                String memory = a[1].replaceAll("\\s+", "");
                if (!memory.matches("[^()]+\\(\\$[A-Za-z0-9]+\\)")) {
                    fail("Expected offset($register): " + a[1]);
                }
                int left = memory.indexOf('(');
                int base = reg(memory.substring(left + 1, memory.length() - 1));
                int displacement = range(number(memory.substring(0, left)), -32768, 32767);
                return iType(op.equals("lw") ? 35 : 43, base, reg(a[0]), displacement);
            case "syscall":
                count(a, 0);
                return 0x0000000C;
            default:
                throw new IllegalArgumentException("Unsupported instruction: " + op);
        }
    }

    // R: opcode(6), rs(5), rt(5), rd(5), shamt(5), funct(6).
    private static int rType(int rs, int rt, int rd, int shift, int function) {
        return (rs << 21) | (rt << 16) | (rd << 11) | (shift << 6) | function;
    }

    // I: opcode(6), rs(5), rt(5), immediate(16).
    private static int iType(int opcode, int rs, int rt, int immediate) {
        return (opcode << 26) | (rs << 21) | (rt << 16) | (immediate & 0xFFFF);
    }

    private int label(String name) {
        Integer result = labels.get(name);
        if (result == null) fail("Undefined label: " + name);
        return result;
    }

    private int codeLabel(String name) {
        int address = label(name);
        if (!textLabels.contains(name)) fail("Branch/jump target is not in .text: " + name);
        return address;
    }

    private int value(String text) {
        if (text.startsWith("hi(") && text.endsWith(")")) {
            return label(text.substring(3, text.length() - 1)) >>> 16;
        }
        if (text.startsWith("lo(") && text.endsWith(")")) {
            return label(text.substring(3, text.length() - 1)) & 65535;
        }
        return number(text);
    }

    private static int reg(String name) {
        name = lowerAscii(name.trim());
        if (name.equals("$s8")) return 30;
        for (int i = 0; i < REGISTER_NAMES.length; i++) {
            if (name.equals("$" + REGISTER_NAMES[i]) || name.equals("$" + i)) return i;
        }
        throw new IllegalArgumentException("Unknown register: " + name);
    }

    private static int number(String text) {
        text = text.trim();
        if (text.startsWith("'")) {
            String character = quoted(text, '\'');
            if (character.length() != 1) fail("Expected one character: " + text);
            return character.charAt(0);
        }
        try {
            boolean negative = text.startsWith("-");
            String digits = text;
            if (digits.startsWith("-") || digits.startsWith("+")) digits = digits.substring(1);
            int radix = 10;
            if (digits.startsWith("0x") || digits.startsWith("0X")) {
                radix = 16;
                digits = digits.substring(2);
            } else if (digits.startsWith("0b") || digits.startsWith("0B")) {
                radix = 2;
                digits = digits.substring(2);
            }
            if (digits.isEmpty() || digits.startsWith("-") || digits.startsWith("+")) {
                fail("Invalid integer: " + text);
            }
            long result = Long.parseLong(digits, radix);
            if (negative) result = -result;
            if (result < Integer.MIN_VALUE || result > 0xFFFFFFFFL) fail("Integer too large: " + text);
            return (int) result;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer: " + text);
        }
    }

    // Keep # inside quoted strings or character literals.
    private static String withoutComment(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#') {
                return text.substring(0, i);
            }
        }
        if (quote != 0) fail("Unclosed quote.");
        return text;
    }

    private static String quoted(String text, char quote) {
        if (text.length() < 2 || text.charAt(0) != quote
                || text.charAt(text.length() - 1) != quote) fail("Expected quoted literal: " + text);
        StringBuilder decoded = new StringBuilder();
        for (int i = 1; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if (c == quote) fail("Unexpected quote: " + text);
            if (c == '\\') {
                if (++i >= text.length() - 1) fail("Incomplete escape.");
                switch (text.charAt(i)) {
                    case 'n': c = '\n'; break;
                    case 'r': c = '\r'; break;
                    case 't': c = '\t'; break;
                    case '0': c = 0; break;
                    case '\\': c = '\\'; break;
                    case '\'': c = '\''; break;
                    case '"': c = '"'; break;
                    default: fail("Unsupported escape: \\" + text.charAt(i));
                }
            }
            if (c > 127) fail("This toy assembler accepts ASCII literals only.");
            decoded.append(c);
        }
        return decoded.toString();
    }

    private static String[] operands(String[] p) {
        if (p.length == 1 || p[1].trim().isEmpty()) return new String[0];
        // Split commas outside quotes so li $a0, ',' also works.
        List<String> result = new ArrayList<>();
        char quote = 0;
        int start = 0;
        for (int i = 0; i < p[1].length(); i++) {
            char c = p[1].charAt(i);
            if (quote != 0) {
                if (c == '\\') i++;
                else if (c == quote) quote = 0;
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == ',') {
                result.add(p[1].substring(start, i).trim());
                start = i + 1;
            }
        }
        result.add(p[1].substring(start).trim());
        return result.toArray(new String[0]);
    }

    private static void count(String[] a, int expected) {
        if (a.length != expected) fail("Expected " + expected + " comma-separated operand(s).");
        for (String operand : a) if (operand.isEmpty()) fail("Empty operand.");
    }

    private static int range(int value, int min, int max) {
        if (value < min || value > max) fail("Value out of range: " + value);
        return value;
    }

    private static void fail(String message) {
        throw new IllegalArgumentException(message);
    }

    // Replace assembler-internal hi/lo expressions with the encoded immediates.
    private String resolvedMips(Instruction instruction) {
        String[] p = instruction.text.split("\\s+", 2);
        String op = lowerAscii(p[0]);
        String[] a = operands(p);
        if (op.equals("lui")) return "lui " + a[0] + ", " + value(a[1]);
        if (op.equals("ori")) return "ori " + a[0] + ", " + a[1] + ", " + value(a[2]);
        return instruction.text;
    }

    static String binaryListingLine(EncodedInstruction encoded) {
        Instruction source = encoded.source;
        String instruction = displayInstructionWithLabels(source.sourceText, encoded.labelLines);
        if (!source.text.equals(source.sourceText)) {
            instruction += " => " + displayInstructionWithLabels(encoded.mips, encoded.labelLines);
        }
        return binary(encoded.word) + " # " + instruction + " # "
               + instructionType(encoded.word) + " # " + machineCodeBreakdown(encoded.word);
    }

    // Annotate only actual label operands, never matching register names,
    // opcodes, numbers, or text inside character literals.
    static String displayInstructionWithLabels(String source, Map<String, Integer> labelLines) {
        String displayed = displayInstruction(source);
        String[] p = source.split("\\s+", 2);
        String op = lowerAscii(p[0]);
        String[] a = operands(p);
        int operand = -1;
        boolean expression = false;
        if (op.equals("j") || op.equals("jal")) operand = 0;
        else if (op.equals("beq") || op.equals("bne")) operand = 2;
        else if (op.equals("la")) operand = 1;
        else if (op.equals("lui")) { operand = 1; expression = true; }
        else if (op.equals("ori") || op.equals("addi") || op.equals("addiu")) {
            operand = 2;
            expression = true;
        }
        if (operand < 0 || operand >= a.length) return displayed;
        String label = a[operand];
        if (expression) {
            if (!(label.startsWith("hi(") || label.startsWith("lo("))
                    || !label.endsWith(")")) return displayed;
            label = label.substring(3, label.length() - 1);
        }
        Integer line = labelLines.get(label);
        if (line == null) return displayed;
        int end = displayed.lastIndexOf(label) + label.length();
        return displayed.substring(0, end) + " (" + line + ")" + displayed.substring(end);
    }

    // Each displayed field is a slice of the actual encoded word.
    static String machineCodeBreakdown(int word) {
        String bits = binary(word);
        int opcode = word >>> 26;
        String fields = "opcode=" + bits.substring(0, 6);
        if (opcode == 2 || opcode == 3) {
            return fields + " target=" + bits.substring(6);
        }
        fields += " rs=" + bits.substring(6, 11) + " rt=" + bits.substring(11, 16);
        if (opcode == 0) {
            return fields + " rd=" + bits.substring(16, 21)
                 + " shamt=" + bits.substring(21, 26) + " funct=" + bits.substring(26);
        }
        return fields + " immediate=" + bits.substring(16);
    }

    // Display-only notation: the ASM parser still expects standard offset[base]
    // written with parentheses in the actual input. Quoted literals keep their
    // values by using hex escapes for forbidden display punctuation.
    static String displayInstruction(String source) {
        StringBuilder result = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (quote == 0 && c == '(') {
                result.append('[');
            } else if (quote == 0 && c == ')') {
                result.append(']');
            } else if (c == '(' || c == ')' || c == '{' || c == '}') {
                result.append("\\x").append(hex(c).substring(6));
            } else {
                result.append(c);
                if (quote != 0 && c == '\\' && i + 1 < source.length()) {
                    char next = source.charAt(i + 1);
                    if (next == quote || next == '\\') {
                        result.append(next);
                        i++;
                    }
                } else if (c == quote) {
                    quote = 0;
                } else if (quote == 0 && (c == '\'' || c == '"')) {
                    quote = c;
                }
            }
        }
        return result.toString();
    }

    private static void appendBinaryInstructions(StringBuilder output, Result result) {
        appendLine(output, ".text @ 0x" + hex(TEXT_BASE) + " - " + result.text.length + " bytes");
        for (EncodedInstruction encoded : result.instructions) {
            appendLine(output, binaryListingLine(encoded));
        }
    }

    private static void appendSection(StringBuilder output, byte[] bytes, String name, int base, int radix) {
        appendLine(output, name + " @ 0x" + hex(base) + " - " + bytes.length + " bytes");
        if (radix == 0) {
            appendLine(output, RAW_CHARACTER_VIEW ? characters(bytes) : printableCharacters(bytes));
            return;
        }
        if (bytes.length % 4 != 0) {
            appendLine(output, "[Final display word is zero-padded; raw data is not padded.]");
        }
        for (int i = 0; i < bytes.length; i += 4) {
            int word = 0;
            for (int j = 0; j < 4; j++) {
                word = (word << 8) | (i + j < bytes.length ? bytes[i + j] & 255 : 0);
            }
            if (radix == 2) {
                StringBuilder values = new StringBuilder(".byte ");
                for (int j = 0; j < 4 && i + j < bytes.length; j++) {
                    if (j > 0) values.append(", ");
                    values.append(bytes[i + j] & 255);
                }
                String bits = binary(word);
                appendLine(output, bits + " # " + values + " # DATA # "
                    + "byte0=" + bits.substring(0, 8) + " byte1=" + bits.substring(8, 16)
                    + " byte2=" + bits.substring(16, 24) + " byte3=" + bits.substring(24));
            } else {
                appendLine(output, hex(word));
            }
        }
    }

    // Formats for the real instructions this toy assembler supports.
    // jr, syscall and nop's sll expansion all use the R encoding.
    static String instructionType(int word) {
        int opcode = word >>> 26;
        if (opcode == 0) return "R-Type";
        if (opcode == 2 || opcode == 3) return "J-Type";
        return "I-Type";
    }

    static String binary(int word) {
        String text = Integer.toBinaryString(word);
        return "00000000000000000000000000000000".substring(text.length()) + text;
    }

    static String hex(int word) {
        String digits = "0123456789ABCDEF";
        StringBuilder text = new StringBuilder();
        for (int shift = 28; shift >= 0; shift -= 4) {
            text.append(digits.charAt((word >>> shift) & 15));
        }
        return text.toString();
    }

    static String characters(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            text.append((char) (bytes[i] & 255));
        }
        return text.toString();
    }

    static String printableCharacters(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        String digits = "0123456789ABCDEF";
        for (byte value : bytes) {
            int b = value & 255;
            if (b == '\\') {
                text.append("\\\\");
            } else if (b >= 32 && b <= 126 && b != '(' && b != ')' && b != '{' && b != '}') {
                text.append((char) b);
            } else {
                text.append("\\x");
                text.append(digits.charAt(b >>> 4));
                text.append(digits.charAt(b & 15));
            }
        }
        return text.toString();
    }

    private static String lowerAscii(String text) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            result.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return result.toString();
    }
}
