package mips;

public class Syscall extends MipsInstr {
    public Syscall() {
        MipsBuilder.addInstr(this);
    }

    public void print() {
        System.out.println("syscall");
    }
}
