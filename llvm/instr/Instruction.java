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
        if (value instanceof Instruction) {
            Instruction instr = (Instruction) value;
            if (!instr.isSpilled() && instr.getAssignedRegister() != null) {
                Register src = instr.getAssignedRegister();
                if (src != to) {
                    new MoveInstr(src, to);
                }
                return;
            }
        }

        if (value instanceof ConstantInt) {
            new LiInstr(to, Integer.parseInt(value.getName()));
            return;
        }
        else if (value instanceof GlobalVariable) {
            new LswInstr("lw", to, ((GlobalVariable) value).getName().substring(1));
            return;
        }
        new LswInstr("lw", to, Register.SP, -value.getMemPos());
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
