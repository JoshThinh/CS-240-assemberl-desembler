# Reconstructed by Disassembler.java from fizzbuzz.mhc-bin (mhc-bin)
# Comments show each word's address and machine code.

.data
str_10010000: .asciiz "Fizz"
str_10010005: .asciiz "Buzz"

.text
.globl main
main:
    addiu $t1, $zero, 1             # 0x00400000: 0x24090001
    addiu $t2, $zero, 101           # 0x00400004: 0x240A0065
L_00400008:
    jal L_0040001C                  # 0x00400008: 0x0C100007
    jal L_0040009C                  # 0x0040000C: 0x0C100027
    addi $t1, $t1, 1                # 0x00400010: 0x21290001
    bne $t1, $t2, L_00400008        # 0x00400014: 0x152AFFFC
    jal L_004000AC                  # 0x00400018: 0x0C10002B
L_0040001C:
    addi $sp, $sp, -8               # 0x0040001C: 0x23BDFFF8
    sw $ra, 0($sp)                  # 0x00400020: 0xAFBF0000
    addiu $at, $zero, 3             # 0x00400024: 0x24010003
    div $t1, $at                    # 0x00400028: 0x0121001A
    mfhi $t3                        # 0x0040002C: 0x00005810
    addiu $at, $zero, 5             # 0x00400030: 0x24010005
    div $t1, $at                    # 0x00400034: 0x0121001A
    mfhi $t4                        # 0x00400038: 0x00006010
    bne $t3, $zero, L_00400044      # 0x0040003C: 0x15600001
    jal L_00400064                  # 0x00400040: 0x0C100019
L_00400044:
    bne $t4, $zero, L_00400050      # 0x00400044: 0x15800002
    jal L_00400078                  # 0x00400048: 0x0C10001E
    j L_00400058                    # 0x0040004C: 0x08100016
L_00400050:
    beq $t3, $zero, L_00400058      # 0x00400050: 0x11600001
    jal L_0040008C                  # 0x00400054: 0x0C100023
L_00400058:
    lw $ra, 0($sp)                  # 0x00400058: 0x8FBF0000
    addi $sp, $sp, 8                # 0x0040005C: 0x23BD0008
    jr $ra                          # 0x00400060: 0x03E00008
L_00400064:
    addiu $v0, $zero, 4             # 0x00400064: 0x24020004
    lui $a0, 4097                   # 0x00400068: 0x3C041001
    ori $a0, $a0, 0                 # 0x0040006C: 0x34840000
    syscall                         # 0x00400070: 0x0000000C
    jr $ra                          # 0x00400074: 0x03E00008
L_00400078:
    addiu $v0, $zero, 4             # 0x00400078: 0x24020004
    lui $a0, 4097                   # 0x0040007C: 0x3C041001
    ori $a0, $a0, 5                 # 0x00400080: 0x34840005
    syscall                         # 0x00400084: 0x0000000C
    jr $ra                          # 0x00400088: 0x03E00008
L_0040008C:
    addiu $v0, $zero, 1             # 0x0040008C: 0x24020001
    addu $a0, $t1, $zero            # 0x00400090: 0x01202021
    syscall                         # 0x00400094: 0x0000000C
    jr $ra                          # 0x00400098: 0x03E00008
L_0040009C:
    addiu $v0, $zero, 11            # 0x0040009C: 0x2402000B
    addiu $a0, $zero, 10            # 0x004000A0: 0x2404000A
    syscall                         # 0x004000A4: 0x0000000C
    jr $ra                          # 0x004000A8: 0x03E00008
L_004000AC:
    addiu $v0, $zero, 10            # 0x004000AC: 0x2402000A
    syscall                         # 0x004000B0: 0x0000000C
