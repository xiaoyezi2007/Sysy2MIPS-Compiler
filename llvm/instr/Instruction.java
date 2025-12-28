package llvm.instr;

import llvm.BasicBlock;
import llvm.Builder;
import llvm.GlobalVariable;
import llvm.IRType;
import llvm.ReturnType;
import llvm.Use;
import llvm.User;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import mips.IInstr;
import mips.JInstr;
import mips.LswInstr;
import mips.MipsBuilder;
import mips.Register;
import mips.fake.LiInstr;
import mips.fake.MoveInstr;

import java.util.ArrayList;

public abstract class Instruction extends User {
    public boolean isPrint = false;
    public boolean isAddr = false;

    private Register assignedRegister = null;
    private boolean spilled = true;

    // Scratch registers reserved for instruction lowering; must not appear in the allocation pool.
    private static final Register[] SCRATCH = new Register[] {
        Register.K0, Register.K1, Register.T8, Register.T9,
        Register.A1, Register.A2, Register.A3
    };

    public BasicBlock earlyBlock = null;
    public BasicBlock lateBlock = null;
    public BasicBlock targetBlock = null;

    public boolean isFloated = false;

    public Instruction(ValueType valueType, IRType Type, String name) {
        super(valueType, Type, name);
    }

    public Instruction(ValueType valueType, IRType Type) {
        super(valueType, Type, Builder.getVarName());
    }

    protected Register tmp(int idx) {
        return SCRATCH[idx];
    }

    public Register getAssignedRegister() {
        return assignedRegister;
    }

    public boolean isSpilled() {
        return spilled;
    }

    public void assignRegister(Register reg) {
        this.assignedRegister = reg;
        this.spilled = false;
    }

    public void spill() {
        this.assignedRegister = null;
        this.spilled = true;
    }

    public void print() {

    }

    public Constant getValue() {
        return null;
    }

    public int getSpace() {
        return 0;
    }

    protected void pushToMem(Register reg) {
        // Keep in register if allocated, otherwise spill to stack.
        if (!isSpilled()) {
            Register target = assignedRegister == null ? reg : assignedRegister;
            if (target != reg) {
                new MoveInstr(reg, target);
            } else if (assignedRegister == null) {
                assignRegister(target);
            }
            return;
        }

        boolean needAlloc = (memory == 1);
        if (needAlloc) {
            memory = MipsBuilder.memory;
        }
        new LswInstr("sw", reg, Register.SP, -memory);
        if (needAlloc) {
            MipsBuilder.memory -= 4;
        }
    }

    protected void pushToMem(Register reg, Instruction instr) {
        if (!instr.isSpilled()) {
            Register target = instr.getAssignedRegister();
            if (target == null) {
                instr.assignRegister(reg);
                return;
            }
            if (target != reg) {
                new MoveInstr(reg, target);
            }
            return;
        }

        if (instr.memory != 1) {
            new LswInstr("sw", reg, Register.SP, -instr.memory);
        }
        else {
            instr.memory = MipsBuilder.memory;
            new LswInstr("sw", reg, Register.SP, -instr.memory);
            MipsBuilder.memory -= 4;
        }
    }

    protected void loadAddrToReg(Value base, Register reg) {
        new IInstr("addiu", reg, Register.SP, -base.getMemPos());
    }

    protected void loadToReg(Value value, Register to) {
        Register src = valueOrLoad(value, to);
        if (src != to) {
            new MoveInstr(src, to);
        }
    }

    /**
     * Return a register that already contains {@code value} if available; otherwise load it into {@code scratch}.
     * This avoids unnecessary moves into scratch registers when values are already resident in alloc-assigned regs.
     */
    protected Register valueOrLoad(Value value, Register scratch) {
        if (value instanceof Instruction) {
            Instruction instr = (Instruction) value;
            if (!instr.isSpilled() && instr.getAssignedRegister() != null) {
                return instr.getAssignedRegister();
            }
        }

        if (value instanceof ConstantInt) {
            new LiInstr(scratch, Integer.parseInt(value.getName()));
            return scratch;
        }
        else if (value instanceof GlobalVariable) {
            new LswInstr("lw", scratch, ((GlobalVariable) value).getName().substring(1));
            return scratch;
        }

        new LswInstr("lw", scratch, Register.SP, -value.getMemPos());
        return scratch;
    }

    public boolean isDef(AllocaInstr instr) {
        return this instanceof StoreInstr && getUseValue(1).equals(instr);
    }

    //Optimize
    public boolean isDead() {
        ArrayList<User> userList = getUserList();
        return userList.isEmpty();
    }

    public boolean isTerminal() {
        return false;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isPinned() {
        return false;
    }

    public int getStackSpace() {
        return isSpilled() ? getSpace() : 0;
    }

    public ArrayList<String> tripleString() {
        return new ArrayList<>();
    }
}
