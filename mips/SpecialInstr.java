package mips;

public class SpecialInstr extends MipsInstr {
    private Register reg;

    public SpecialInstr(String op, Register reg) {
        this.op = op;
        this.reg = reg;
        MipsBuilder.addInstr(this);
    }

    /**
     * Optimization-friendly constructor.
     * When emit is false, the instruction is created detached and must be inserted into a list manually.
     */
    public SpecialInstr(String op, Register reg, boolean emit) {
        this.op = op;
        this.reg = reg;
        if (emit) {
            MipsBuilder.addInstr(this);
        }
    }

    public void print() {
        System.out.println(op+" "+reg.toString());
    }

    public Register getReg() {
        return reg;
    }
}
