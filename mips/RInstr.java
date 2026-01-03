package mips;

public class RInstr extends MipsInstr {
    private Register left;
    private Register right;
    private Register ans;

    public RInstr(String op, Register ans, Register left, Register right) {
        this.ans = ans;
        this.left = left;
        this.right = right;
        this.op = op;
        MipsBuilder.addInstr(this);
    }

    /**
     * Optimization-friendly constructor.
     * When emit is false, the instruction is created detached and must be inserted into a list manually.
     */
    public RInstr(String op, Register ans, Register left, Register right, boolean emit) {
        this.ans = ans;
        this.left = left;
        this.right = right;
        this.op = op;
        if (emit) {
            MipsBuilder.addInstr(this);
        }
    }

    public void print() {
        if (ans == Register.HI || ans == Register.LO) {
            System.out.println(op+" "+left.toString()+" "+right.toString());
        }
        else {
            System.out.println(op+" "+ans.toString()+" "+left.toString()+" "+right.toString());
        }
    }

    public Register getAns() {
        return ans;
    }

    public Register getLeft() {
        return left;
    }

    public Register getRight() {
        return right;
    }
}
