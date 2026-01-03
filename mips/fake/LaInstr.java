package mips.fake;

import mips.MipsBuilder;
import mips.MipsInstr;
import mips.Register;

public class LaInstr extends MipsInstr {
    private Register to;
    private String addr;

    public LaInstr(Register to, String addr) {
        this(to, addr, true);
    }

    /**
     * Optimization-friendly constructor.
     * When emit is false, the instruction is created detached and must be inserted into a list manually.
     */
    public LaInstr(Register to, String addr, boolean emit) {
        this.to = to;
        this.addr = addr;
        if (emit) {
            MipsBuilder.addInstr(this);
        }
    }

    public void print() {
        System.out.println("la " + to.toString() + " " + addr);
    }

    public Register getTo() {
        return to;
    }

    public String getAddr() {
        return addr;
    }
}
