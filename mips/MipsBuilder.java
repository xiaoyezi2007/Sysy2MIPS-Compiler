package mips;

import llvm.Function;

public class MipsBuilder {
    public static MipsModule module = new MipsModule();
    public static Function curFunc;
    public static int memory = 0;
    public static boolean isMain = false;

    public MipsBuilder() {}

    public static void resetSP() {
        int tmp = memory;
        new IInstr("addi", Register.SP, Register.SP, -memory);
        memory = tmp;
    }

    public static void addString(MipsString string) {
        module.addString(string);
    }

    public static void addWord(MipsWord word) {
        module.addWord(word);
    }

    public static void addInstr(MipsInstr instr) {
        module.addInstr(instr);
    }
}
