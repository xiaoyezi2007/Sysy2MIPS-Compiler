package mips;

public class SpecialInstr extends MipsInstr {
    private Register reg;

    public SpecialInstr(String op, Register reg) {
        this.op = op;
        this.reg = reg;
        MipsBuilder.addInstr(this);
    }

    public void print() {
        System.out.println(op+" "+reg.toString());
    }
}
