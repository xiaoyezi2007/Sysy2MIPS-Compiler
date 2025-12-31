package llvm;

import llvm.instr.BranchInstr;
import llvm.instr.Instruction;
import llvm.instr.MoveInstr;
import llvm.instr.PhiInstr;
import llvm.instr.RetInstr;
import mips.Label;
import mips.LswInstr;
import mips.Register;
import mips.fake.LiInstr;

import java.util.ArrayList;
import java.util.HashSet;

public class BasicBlock extends Value {
    private Function fatherFunction;
    ArrayList<Instruction> instructions = new ArrayList<>();

    public HashSet<BasicBlock> next = new HashSet<>();
    public HashSet<BasicBlock> prev = new HashSet<>();
    public HashSet<BasicBlock> dom = new HashSet<>(); // block in array dom this block
    public HashSet<BasicBlock> dominanceFrontier = new HashSet<>();
    public BasicBlock directDom = null;
    public ArrayList<BasicBlock> domTreeNext = new ArrayList<>();
    public int cycleDepth = 0;
    public int domDepth = -1;

    public HashSet<Instruction> liveIn = new HashSet<>();
    public HashSet<Instruction> liveOut = new HashSet<>();
    public HashSet<Instruction> useReg = new HashSet<>();
    public HashSet<Instruction> defReg = new HashSet<>();

    public BasicBlock() {
        super(ValueType.BASIC_BLOCK, new IRType("void"), "block");
    }

    public void initUseDef() {
        for (Instruction instruction : instructions) {
            ArrayList<Value> operands = instruction.getOperands();
            for (Value operand : operands) {
                if (operand instanceof Instruction) {
                    useReg.add((Instruction) operand);
                }
            }
            if (!instruction.getType().equals(new IRType("void"))) {
                defReg.add(instruction);
            }
        }
    }

    public boolean updateLive() {
        boolean flag = false;
        HashSet<Instruction> newIn = new HashSet<>(useReg);
        for (Instruction instruction : liveOut) {
            if (!defReg.contains(instruction)) {
                newIn.add(instruction);
            }
        }
        if (!newIn.equals(liveIn)) {
            flag = true;
            liveIn = newIn;
        }
        HashSet<Instruction> newOut = new HashSet<>();
        for (BasicBlock block : next) {
            newOut.addAll(block.liveIn);
        }
        if (!newOut.equals(liveOut)) {
            flag = true;
            liveOut = newOut;
        }
        return flag;
    }

    public void updateDomDepth(int x) {
        domDepth = x;
        for (BasicBlock b : domTreeNext) {
            b.updateDomDepth(x+1);
        }
    }

    public void removeInstr(Instruction instr) {
        instructions.remove(instr);
    }

    public void insertInstrBeforeTerminal(Instruction instr) {
        instructions.add(instructions.size()-1, instr);
    }

    public void addMove(Value from, Value to) {
        instructions.add(instructions.size()-1, new MoveInstr(from, to));
    }

    public void replaceNextBlock(BasicBlock from, BasicBlock to) {
        Instruction instr = instructions.get(instructions.size()-1);
        if (instr instanceof BranchInstr) {
            ((BranchInstr) instr).replaceBlock(from, to);
        }
    }

    public void replacePrevBlock(BasicBlock from, BasicBlock to) {
        for (Instruction instr : instructions) {
            if (!(instr instanceof PhiInstr)) {
                break;
            }
            PhiInstr phi = (PhiInstr) instr;
            phi.replaceBlock(from, to);
        }
    }

    public void addDF(BasicBlock b) {
        dominanceFrontier.add(b);
    }

    public void insertPhi(PhiInstr instr) {
        instructions.add(0, instr);
    }

    public boolean updateDom() {
        HashSet<BasicBlock> newDom = new HashSet<>();
        for (BasicBlock block : prev) {
            if (newDom.isEmpty()) {
                newDom.addAll(block.dom);
            }
            else {
                newDom.retainAll(block.dom);
            }
        }
        newDom.add(this);
        if (newDom.equals(dom)) {
            return false;
        }
        dom = newDom;
        return true;
    }

    public void initDom(ArrayList<BasicBlock> dom) {
        this.dom.addAll(dom);
    }

    public void updateDirectDom() {
        if (dom.size() == 1) {
            return;
        }
        for (BasicBlock block : dom) {
            boolean flag = true;
            for (BasicBlock block2 : dom) {
                if (block2.isStrictDominate(block) && !block2.equals(this)) {
                    flag = false;
                    break;
                }
            }
            if (flag && !block.equals(this)) {
                directDom = block;
                block.domTreeNext.add(this);
                break;
            }
        }
    }

    public boolean isDominate(BasicBlock block) {
        return dom.contains(block);
    }

    public boolean isStrictDominate(BasicBlock block) {  // be dominated
        return dom.contains(block) && !block.equals(this);
    }

    public void setFatherFunction(Function fatherFunction) {
        this.fatherFunction = fatherFunction;
    }

    public void addNextBlock(BasicBlock nextBlock) {
        next.add(nextBlock);
    }

    public void addPrevBlock(BasicBlock prevBlock) {
        prev.add(prevBlock);
    }

    public void removeNextBlock(BasicBlock nextBlock) {
        next.remove(nextBlock);
    }

    public void removePrevBlock(BasicBlock prevBlock) {
        prev.remove(prevBlock);
    }

    public void popInstr() {
        instructions.remove(instructions.size() - 1);
    }

    public ArrayList<Instruction> getInstructions() {
        return instructions;
    }

    public void addInstruction(Instruction instruction) {
        instructions.add(instruction);
    }

    public void rmInstruction(int i) {
        instructions.remove(i);
    }

    public void rmInstruction(Instruction instruction) {
        instructions.remove(instruction);
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public boolean isReturn() {
        return !instructions.isEmpty() && instructions.get(instructions.size()-1) instanceof RetInstr;
    }

    public String getMipsLabel() {
        return fatherFunction.name+"."+getName();
    }

    public int getSpace() {
        int ans = 0;
        for (Instruction instruction : instructions) {
            ans+=instruction.getSpace();
        }
        return ans;
    }

    @Override
    public void toMips() {
        if (instructions.isEmpty()) {
            return;
        }

        // If a block ends with a terminal and has a contiguous run of MoveInstr right before it,
        // treat those moves as a parallel copy group (phi lowering pattern).
        int last = instructions.size() - 1;
        Instruction terminal = instructions.get(last);
        boolean isTerminal = (terminal instanceof BranchInstr) || terminal.isTerminal() || (terminal instanceof RetInstr);
        if (!isTerminal || last == 0) {
            for (Instruction instruction : instructions) {
                instruction.toMips();
            }
            return;
        }

        int moveEnd = last - 1;
        int moveStart = moveEnd;
        while (moveStart >= 0 && instructions.get(moveStart) instanceof MoveInstr) {
            moveStart--;
        }
        moveStart++;

        // No trailing moves.
        if (moveStart > moveEnd) {
            for (Instruction instruction : instructions) {
                instruction.toMips();
            }
            return;
        }

        // Emit prefix.
        for (int i = 0; i < moveStart; i++) {
            instructions.get(i).toMips();
        }

        // Emit trailing moves as parallel copy.
        ArrayList<MoveInstr> moves = new ArrayList<>();
        for (int i = moveStart; i <= moveEnd; i++) {
            moves.add((MoveInstr) instructions.get(i));
        }
        emitParallelMoves(moves);

        // Emit terminal.
        terminal.toMips();
    }

    private void emitParallelMoves(ArrayList<MoveInstr> moves) {
        // First, emit memory destinations early to preserve old source values.
        ArrayList<MoveInstr> regMoves = new ArrayList<>();
        for (MoveInstr mi : moves) {
            Value dstV = mi.getUseValue(1);
            if (!(dstV instanceof Instruction)) {
                mi.toMips();
                continue;
            }
            Instruction dstI = (Instruction) dstV;
            if (dstI.isSpilled() || dstI.getAssignedRegister() == null) {
                mi.toMips();
            } else {
                regMoves.add(mi);
            }
        }

        // Now resolve register destinations with a standard parallel copy algorithm.
        // Scratch: K0 for cycle break temp, K1 for materializing constants/loads.
        class RM {
            final Register dst;
            final Value src;
            final Register srcReg; // non-null only if src is a resident register value
            RM(Register dst, Value src, Register srcReg) {
                this.dst = dst;
                this.src = src;
                this.srcReg = srcReg;
            }
        }

        ArrayList<RM> pending = new ArrayList<>();
        for (MoveInstr mi : regMoves) {
            Value src = mi.getUseValue(0);
            Instruction dstI = (Instruction) mi.getUseValue(1);
            Register dst = dstI.getAssignedRegister();

            Register srcReg = null;
            if (src instanceof Instruction) {
                Instruction si = (Instruction) src;
                if (!si.isSpilled() && si.getAssignedRegister() != null) {
                    srcReg = si.getAssignedRegister();
                }
            }

            // Skip trivial self-moves.
            if (srcReg != null && srcReg == dst) {
                continue;
            }
            pending.add(new RM(dst, src, srcReg));
        }

        while (!pending.isEmpty()) {
            // Collect source registers among remaining moves.
            java.util.HashSet<Register> srcRegs = new java.util.HashSet<>();
            for (RM m : pending) {
                if (m.srcReg != null) {
                    srcRegs.add(m.srcReg);
                }
            }

            int pick = -1;
            for (int i = 0; i < pending.size(); i++) {
                RM m = pending.get(i);
                if (!srcRegs.contains(m.dst)) {
                    pick = i;
                    break;
                }
            }

            if (pick != -1) {
                RM m = pending.remove(pick);
                Register srcR = (m.srcReg != null) ? m.srcReg : materializeToReg(m.src, Register.K1);
                if (srcR != m.dst) {
                    new mips.fake.MoveInstr(srcR, m.dst);
                }
                continue;
            }

            // Cycle: break by saving one source into K0, then rewrite that move to read from K0.
            RM cyc = pending.get(0);
            Register srcR = (cyc.srcReg != null) ? cyc.srcReg : materializeToReg(cyc.src, Register.K0);
            if (srcR != Register.K0) {
                new mips.fake.MoveInstr(srcR, Register.K0);
            }
            // Rewrite: dst <- K0 (K0 is not used as any destination).
            pending.set(0, new RM(cyc.dst, null, Register.K0));
        }
    }

    private Register materializeToReg(Value v, Register scratch) {
        if (v instanceof llvm.constant.ConstantInt) {
            int imm = Integer.parseInt(v.getName());
            new LiInstr(scratch, imm);
            return scratch;
        }
        if (v instanceof Instruction) {
            Instruction ins = (Instruction) v;
            if (!ins.isSpilled() && ins.getAssignedRegister() != null) {
                return ins.getAssignedRegister();
            }
            // spilled SSA value: load from its stack slot
            new LswInstr("lw", scratch, Register.SP, -v.getMemPos());
            return scratch;
        }
        // Fallback: treat as stack resident value.
        new LswInstr("lw", scratch, Register.SP, -v.getMemPos());
        return scratch;
    }

    public void print() {
        for (Instruction instruction : instructions) {
            instruction.print();
        }
    }
}
