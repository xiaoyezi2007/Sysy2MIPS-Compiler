package mips.fake;

import mips.MipsBuilder;
import mips.MipsInstr;
import mips.Register;
import mips.Syscall;

public class MoveInstr extends MipsInstr {
    private Register from;
    private Register to;

    public MoveInstr(Register from, Register to) {
        this(from, to, true);
    }

    public MoveInstr(Register from, Register to, boolean emit) {
        this.from = from;
        this.to = to;
        if (emit) {
            MipsBuilder.addInstr(this);
        }
    }

    public void print() {
        System.out.println("move "+ to.toString() + " " + from.toString());
    }

    public Register getFrom() {
        return from;
    }

    public Register getTo() {
        return to;
    }
}
