package mips;

public class LswInstr extends MipsInstr {
    private Register rs;
    private Register rt;
    private int immediate;
    private String label = "";

    public LswInstr(String op, Register rs, Register rt, int immediate) {
        this.op = op;
        this.rs = rs;
        this.rt = rt;
        this.immediate = immediate;
        MipsBuilder.addInstr(this);
    }

    /**
     * Optimization-friendly constructor.
     * When emit is false, the instruction is created detached and must be inserted into a list manually.
     */
    public LswInstr(String op, Register rs, Register rt, int immediate, boolean emit) {
        this.op = op;
        this.rs = rs;
        this.rt = rt;
        this.immediate = immediate;
        if (emit) {
            MipsBuilder.addInstr(this);
        }
    }

    public LswInstr(String op, Register rs, String label) {
        this.op = op;
        this.rs = rs;
        this.label = label;
        MipsBuilder.addInstr(this);
    }

    public boolean isLabelMode() {
        return label != null && !label.isEmpty();
    }

    public String getLabel() {
        return label;
    }

    /**
     * For lw/sw: rs is the data register.
     * - lw: destination
     * - sw: source
     */
    public Register getRs() {
        return rs;
    }

    /**
     * For lw/sw: rt is the base register.
     */
    public Register getRt() {
        return rt;
    }

    public int getImmediate() {
        return immediate;
    }

    public void print() {
        if (!label.isEmpty()) {
            System.out.println(op+" "+rs.toString()+" "+label);
        }
        else {
            System.out.println(op+" "+rs.toString()+" "+immediate+"("+rt.toString()+")");
        }
    }
}
