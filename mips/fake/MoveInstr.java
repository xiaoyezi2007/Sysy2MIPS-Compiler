package mips.fake;

import mips.MipsBuilder;
import mips.MipsInstr;
import mips.Register;
import mips.Syscall;

public class MoveInstr extends MipsInstr {
    private Register from;
    private Register to;

    public MoveInstr(Register from, Register to) {
        this.from = from;
        this.to = to;
        MipsBuilder.addInstr(this);
    }

    public void print() {
        System.out.println("move "+ to.toString() + " " + from.toString());
    }
}
