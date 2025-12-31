package optimizer;

import mips.IInstr;
import mips.JInstr;
import mips.Label;
import mips.LswInstr;
import mips.MipsInstr;
import mips.MipsModule;
import mips.MipsString;
import mips.MipsWord;
import mips.Register;
import mips.RInstr;
import mips.SpecialInstr;
import mips.Syscall;
import mips.fake.LaInstr;
import mips.fake.LiInstr;
import mips.fake.MoveInstr;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class Optimizer {
    private MipsModule mipsModule;
    private ArrayList<MipsInstr> Instrs;
    private ArrayList<MipsWord> Words;
    private ArrayList<MipsString> Strings;

    public Optimizer(MipsModule mipsModule) {
        this.mipsModule = mipsModule;
    }

    public MipsModule optimize() {
        Instrs = mipsModule.getInstrs();
        Words = mipsModule.getWords();
        Strings = mipsModule.getStrings();
        MipsModule optimizedModule = new MipsModule();
        for (MipsWord word : Words) {
            optimizedModule.addWord(word);
        }
        for (MipsString string : Strings) {
            optimizedModule.addString(string);
        }

        //multDivOptimize();
        pesudoInstrOptimize();
        peepholeOptimize();

        for (MipsInstr instr : Instrs) {
            optimizedModule.addInstr(instr);
        }

        return optimizedModule;
    }

    public void multDivOptimize() {
        ArrayList<MipsInstr> optInstrs = new ArrayList<>();
        for (int i = 0; i < Instrs.size(); i++) {
            MipsInstr instr = Instrs.get(i);
            if (instr instanceof IInstr && instr.getOp().equals("mul")) {}
        }
    }

    public void pesudoInstrOptimize() {
        ArrayList<MipsInstr> optInstrs = new ArrayList<>();
        for (int i = 0; i < Instrs.size(); i++) {
            MipsInstr instr = Instrs.get(i);
            if (instr instanceof IInstr && instr.getOp().equals("subi")) {
                IInstr iInstr = (IInstr) instr;
                int x = (iInstr).getImmediate();
                optInstrs.add(new IInstr("addi", iInstr.getRs(), iInstr.getRt(), -x));
            }
            else {
                optInstrs.add(instr);
            }
        }
        Instrs = optInstrs;
    }

    public void peepholeOptimize() {
        // Pass 1: basic-block-local stack-slot peephole (no cross-CFG propagation).
        ArrayList<MipsInstr> out = new ArrayList<>();
        Map<Integer, SlotState> slotStates = new HashMap<>();

        for (MipsInstr instr : Instrs) {
            // Basic block boundary: label.
            if (instr instanceof Label) {
                clearSlotStates(slotStates);
                out.add(instr);
                continue;
            }

            // Hard barriers.
            if (instr instanceof Syscall) {
                clearSlotStates(slotStates);
                out.add(instr);
                clearSlotStates(slotStates);
                continue;
            }
            if (instr instanceof JInstr) {
                clearSlotStates(slotStates);
                out.add(instr);
                clearSlotStates(slotStates);
                continue;
            }
            if (instr instanceof IInstr) {
                IInstr iInstr = (IInstr) instr;
                // Branch-like IInstr (beq/bne/...) ends the block.
                if (iInstr.getRt() == null) {
                    clearSlotStates(slotStates);
                    out.add(instr);
                    clearSlotStates(slotStates);
                    continue;
                }
                // Stack pointer adjustment changes addressing for all $sp slots.
                if (iInstr.getRs() == Register.SP) {
                    clearSlotStates(slotStates);
                    out.add(instr);
                    clearSlotStates(slotStates);
                    continue;
                }
            }

            // Memory ops: only optimize clean word-aligned k($sp) accesses.
            if (instr instanceof LswInstr) {
                LswInstr mem = (LswInstr) instr;

                if (!isEligibleSpSlot(mem)) {
                    clearSlotStates(slotStates);
                    out.add(instr);
                    clearSlotStates(slotStates);
                    continue;
                }

                int slot = mem.getImmediate();
                SlotState state = slotStates.computeIfAbsent(slot, k -> new SlotState());

                if (mem.getOp().equals("lw")) {
                    Register dst = mem.getRs();

                    Register src = state.knownReg;
                    if (src == null) {
                        src = state.lastStoredReg;
                    }

                    if (src != null) {
                        // Replace lw with move (or delete if dst already equals src).
                        if (dst != src) {
                            out.add(new MoveInstr(src, dst, false));
                        }
                        state.readSinceLastStore = true;
                        // Invalidate other cached uses of dst, then record dst for this slot.
                        invalidateByDefinedReg(slotStates, dst);
                        state.knownReg = dst;
                        continue;
                    }

                    // Can't forward: keep the load, but update cache info.
                    out.add(mem);
                    state.readSinceLastStore = true;
                    invalidateByDefinedReg(slotStates, dst);
                    state.knownReg = dst;
                    continue;
                }

                if (mem.getOp().equals("sw")) {
                    // Dead store elimination (within the block):
                    // sw ..., slot; ...; sw ..., slot  and no intervening read from slot.
                    if (!state.readSinceLastStore && state.lastStoreOutIndex >= 0) {
                        out.set(state.lastStoreOutIndex, null);
                    }

                    out.add(mem);
                    state.lastStoreOutIndex = out.size() - 1;
                    state.readSinceLastStore = false;

                    Register src = mem.getRs();
                    state.lastStoredReg = src;
                    state.knownReg = src;
                    continue;
                }

                // Unknown mem op: act as barrier.
                clearSlotStates(slotStates);
                out.add(instr);
                clearSlotStates(slotStates);
                continue;
            }

            // Normal instruction: emit, then invalidate caches based on defined register.
            out.add(instr);
            Register defined = getDefinedRegister(instr);
            if (defined != null) {
                invalidateByDefinedReg(slotStates, defined);
            }
        }

        // Filter nulls from dead-store elimination.
        ArrayList<MipsInstr> pass1 = new ArrayList<>();
        for (MipsInstr instr : out) {
            if (instr != null) {
                pass1.add(instr);
            }
        }

        // Pass 2: j L; L:  => remove j
        ArrayList<MipsInstr> pass2 = new ArrayList<>();
        int i = 0;
        while (i < pass1.size()) {
            MipsInstr now = pass1.get(i);
            if (i < pass1.size() - 1) {
                MipsInstr nxt = pass1.get(i + 1);
                if (now instanceof JInstr && nxt instanceof Label) {
                    JInstr j = (JInstr) now;
                    Label l = (Label) nxt;
                    if (j.getOp().equals("j") && j.getLabel().equals(l.getName())) {
                        i++;
                        continue;
                    }
                }
            }
            pass2.add(now);
            i++;
        }

        // Pass 3: coalesce move into following branch/memory op
        // Pattern (typically from lw->move forwarding):
        //   move rD rS
        //   beq/bne rD imm, L
        // =>
        //   beq/bne rS imm, L
        // This shortens hot loop conditions without changing semantics.
        ArrayList<MipsInstr> pass3 = new ArrayList<>();
        int j = 0;
        while (j < pass2.size()) {
            MipsInstr cur = pass2.get(j);

            if (cur instanceof MoveInstr && j + 1 < pass2.size()) {
                MoveInstr mv = (MoveInstr) cur;
                MipsInstr nxt = pass2.get(j + 1);

                // IMPORTANT: Only delete the move when its destination register is proven dead
                // (not used before being redefined) within the current basic block. Otherwise,
                // removing it can leave later uses reading an uninitialized register.
                boolean canDeleteMove = !isUsedBeforeRedefOrBlockEnd(pass2, j + 2, mv.getTo());

                // move -> branch
                if (nxt instanceof IInstr) {
                    IInstr br = (IInstr) nxt;
                    if (br.getRt() == null) {
                        String op = br.getOp();
                        if (("beq".equals(op) || "bne".equals(op)) && br.getRs() == mv.getTo()) {
                            // Keep the move if its destination is still needed later.
                            if (!canDeleteMove) {
                                pass3.add(cur);
                            }
                            pass3.add(new IInstr(op, mv.getFrom(), br.getImmediate(), br.getLabel(), false));
                            j += 2;
                            continue;
                        }
                    }
                }

                // move -> lw/sw (rewrite base or store source)
                if (nxt instanceof LswInstr) {
                    LswInstr mem = (LswInstr) nxt;
                    if (!mem.isLabelMode()) {
                        Register newRs = mem.getRs();
                        Register newRt = mem.getRt();

                        // lw: base register can be rewritten
                        if ("lw".equals(mem.getOp()) && mem.getRt() == mv.getTo()) {
                            newRt = mv.getFrom();
                        }
                        // sw: source register can be rewritten
                        if ("sw".equals(mem.getOp()) && mem.getRs() == mv.getTo()) {
                            newRs = mv.getFrom();
                        }

                        if (newRs != mem.getRs() || newRt != mem.getRt()) {
                            // Keep the move if its destination is still needed later.
                            if (!canDeleteMove) {
                                pass3.add(cur);
                            }
                            pass3.add(new LswInstr(mem.getOp(), newRs, newRt, mem.getImmediate(), false));
                            j += 2;
                            continue;
                        }
                    }
                }
            }

            pass3.add(cur);
            j++;
        }

        Instrs = pass3;

        // Pass 4: remove redundant compare-result spill before branch
        // Pattern:
        //   <defines rD>          (e.g. slti rD, rS, imm  OR  slt/seq/...)
        //   sw rD, off($sp)
        //   beq/bne rD, {0|1}, L
        // The store exists only to support a following reload for the branch;
        // after other peepholes, the branch already uses rD directly, so the sw is dead.
        ArrayList<MipsInstr> pass4 = new ArrayList<>();
        int k = 0;
        while (k < Instrs.size()) {
            if (k + 2 < Instrs.size()) {
                MipsInstr a = Instrs.get(k);
                MipsInstr b = Instrs.get(k + 1);
                MipsInstr c = Instrs.get(k + 2);

                Register def = getDefinedRegister(a);
                if (def != null && b instanceof LswInstr && c instanceof IInstr) {
                    LswInstr sw = (LswInstr) b;
                    IInstr br = (IInstr) c;

                    boolean isSwToSp = "sw".equals(sw.getOp()) && !sw.isLabelMode() && sw.getRt() == Register.SP;
                    boolean isBranch = br.getRt() == null && ("beq".equals(br.getOp()) || "bne".equals(br.getOp()));
                    boolean branchUsesDef = br.getRs() == def;
                    int imm = br.getImmediate();
                    boolean immOk = (imm == 0 || imm == 1);

                    if (isSwToSp && sw.getRs() == def && isBranch && branchUsesDef && immOk) {
                        pass4.add(a);
                        pass4.add(c);
                        k += 3;
                        continue;
                    }
                }
            }
            pass4.add(Instrs.get(k));
            k++;
        }

        Instrs = pass4;

        // Pass 5: eliminate unconditional jump via fallthrough
        // Pattern:
        //   beq/bne r, imm, T
        //   j F
        // T:
        // =>
        //   bne/beq r, imm, F
        // T:
        // This is safe because T is the immediate fallthrough label.
        ArrayList<MipsInstr> pass5 = new ArrayList<>();
        int p = 0;
        while (p < Instrs.size()) {
            if (p + 2 < Instrs.size() && Instrs.get(p) instanceof IInstr && Instrs.get(p + 1) instanceof JInstr
                    && Instrs.get(p + 2) instanceof Label) {
                IInstr br = (IInstr) Instrs.get(p);
                JInstr jmp = (JInstr) Instrs.get(p + 1);
                Label fall = (Label) Instrs.get(p + 2);

                boolean isCondBr = br.getRt() == null && ("beq".equals(br.getOp()) || "bne".equals(br.getOp()));
                boolean isJ = "j".equals(jmp.getOp());
                if (isCondBr && isJ && br.getLabel() != null && br.getLabel().equals(fall.getName())) {
                    String inv = "beq".equals(br.getOp()) ? "bne" : "beq";
                    pass5.add(new IInstr(inv, br.getRs(), br.getImmediate(), jmp.getLabel(), false));
                    pass5.add(fall);
                    p += 3;
                    continue;
                }
            }
            pass5.add(Instrs.get(p));
            p++;
        }

        Instrs = pass5;

        // Pass 6: eliminate boolean invert feeding a branch
        // Pattern:
        //   xori t, s, 1
        //   beq/bne t, {0|1}, L
        // where s is a boolean (0/1) value.
        // Rewrite the branch to use s directly and drop the xori.
        ArrayList<MipsInstr> pass6 = new ArrayList<>();
        int q = 0;
        while (q < Instrs.size()) {
            if (q + 1 < Instrs.size() && Instrs.get(q) instanceof IInstr && Instrs.get(q + 1) instanceof IInstr) {
                IInstr inv = (IInstr) Instrs.get(q);
                IInstr br = (IInstr) Instrs.get(q + 1);

                boolean isXori = inv.getRt() != null && "xori".equals(inv.getOp()) && inv.getImmediate() == 1;
                boolean isBr = br.getRt() == null && ("beq".equals(br.getOp()) || "bne".equals(br.getOp()));
                boolean immIs01 = br.getImmediate() == 0 || br.getImmediate() == 1;

                if (isXori && isBr && immIs01 && br.getRs() == inv.getRs()) {
                    Register src = inv.getRt();

                    // Be conservative: only remove invert if src is produced by a boolean-setting instruction.
                    boolean srcIsBool = false;
                    if (q - 1 >= 0) {
                        MipsInstr def = Instrs.get(q - 1);
                        Register defReg = getDefinedRegister(def);
                        if (defReg == src) {
                            if (def instanceof IInstr) {
                                String op = def.getOp();
                                srcIsBool = "slti".equals(op) || "sltiu".equals(op);
                            }
                            else if (def instanceof RInstr) {
                                String op = def.getOp();
                                srcIsBool = "seq".equals(op) || "sne".equals(op) || "slt".equals(op)
                                        || "sle".equals(op) || "sgt".equals(op) || "sge".equals(op);
                            }
                        }
                    }

                    if (srcIsBool) {
                        String newOp = br.getOp();
                        int newImm;
                        if (br.getImmediate() == 1) {
                            // (s^1 == 1) <=> (s == 0)
                            // (s^1 != 1) <=> (s != 0)
                            newImm = 0;
                        }
                        else {
                            // (s^1 == 0) <=> (s == 1) <=> (s != 0)
                            // (s^1 != 0) <=> (s != 1) <=> (s == 0)
                            newImm = 0;
                            newOp = "beq".equals(br.getOp()) ? "bne" : "beq";
                        }

                        pass6.add(new IInstr(newOp, src, newImm, br.getLabel(), false));
                        q += 2;
                        continue;
                    }
                }
            }

            pass6.add(Instrs.get(q));
            q++;
        }

        Instrs = pass6;

        // Pass 7: normalize boolean branches to compare against 0
        // Pattern:
        //   beq/bne r, 1, L   (r is boolean 0/1)
        // =>
        //   bne/beq r, 0, L
        // This canonical form improves downstream matching.
        ArrayList<MipsInstr> pass7 = new ArrayList<>();
        for (int idx = 0; idx < Instrs.size(); idx++) {
            MipsInstr ins = Instrs.get(idx);
            if (ins instanceof IInstr) {
                IInstr br = (IInstr) ins;
                boolean isBr = br.getRt() == null && ("beq".equals(br.getOp()) || "bne".equals(br.getOp()));
                if (isBr && br.getImmediate() == 1) {
                    Register r = br.getRs();
                    boolean rIsBool = false;
                    if (idx - 1 >= 0) {
                        MipsInstr def = Instrs.get(idx - 1);
                        Register defReg = getDefinedRegister(def);
                        if (defReg == r) {
                            if (def instanceof IInstr) {
                                String op = def.getOp();
                                rIsBool = "slti".equals(op) || "sltiu".equals(op);
                            }
                            else if (def instanceof RInstr) {
                                String op = def.getOp();
                                rIsBool = "seq".equals(op) || "sne".equals(op) || "slt".equals(op)
                                        || "sle".equals(op) || "sgt".equals(op) || "sge".equals(op);
                            }
                        }
                    }
                    if (rIsBool) {
                        String flipped = "beq".equals(br.getOp()) ? "bne" : "beq";
                        pass7.add(new IInstr(flipped, r, 0, br.getLabel(), false));
                        continue;
                    }
                }
            }
            pass7.add(ins);
        }
        Instrs = pass7;

        // Pass 8: remove shift-by-0 (sll/srl/sra)
        ArrayList<MipsInstr> pass8 = new ArrayList<>();
        for (MipsInstr ins : Instrs) {
            if (ins instanceof IInstr) {
                IInstr sh = (IInstr) ins;
                if (sh.getRt() != null && ("sll".equals(sh.getOp()) || "srl".equals(sh.getOp()) || "sra".equals(sh.getOp()))
                        && sh.getImmediate() == 0) {
                    if (sh.getRs() == sh.getRt()) {
                        continue;
                    }
                    pass8.add(new MoveInstr(sh.getRt(), sh.getRs(), false));
                    continue;
                }
            }
            pass8.add(ins);
        }
        Instrs = pass8;

        // Pass 9: remove obvious no-ops / replace ops with move when one operand is $zero
        ArrayList<MipsInstr> pass9 = new ArrayList<>();
        for (MipsInstr ins : Instrs) {
            if (ins instanceof MoveInstr) {
                MoveInstr mv = (MoveInstr) ins;
                if (mv.getFrom() == mv.getTo()) {
                    continue;
                }
            }
            if (ins instanceof IInstr) {
                IInstr it = (IInstr) ins;
                if (it.getRt() != null && ("addi".equals(it.getOp()) || "addiu".equals(it.getOp())) && it.getImmediate() == 0) {
                    if (it.getRs() == it.getRt()) {
                        continue;
                    }
                    pass9.add(new MoveInstr(it.getRt(), it.getRs(), false));
                    continue;
                }
                if (it.getRt() != null && "xori".equals(it.getOp()) && it.getImmediate() == 0) {
                    if (it.getRs() == it.getRt()) {
                        continue;
                    }
                    pass9.add(new MoveInstr(it.getRt(), it.getRs(), false));
                    continue;
                }
            }
            if (ins instanceof RInstr) {
                RInstr r = (RInstr) ins;
                if ("addu".equals(r.getOp())) {
                    if (r.getLeft() == Register.ZERO) {
                        pass9.add(new MoveInstr(r.getRight(), r.getAns(), false));
                        continue;
                    }
                    if (r.getRight() == Register.ZERO) {
                        pass9.add(new MoveInstr(r.getLeft(), r.getAns(), false));
                        continue;
                    }
                }
                if ("subu".equals(r.getOp()) && r.getRight() == Register.ZERO) {
                    pass9.add(new MoveInstr(r.getLeft(), r.getAns(), false));
                    continue;
                }
            }
            pass9.add(ins);
        }
        Instrs = pass9;

        // Pass 10: remove redundant "lw slot; sw slot" back-to-same-stack-slot
        // Pattern:
        //   lw r, off($sp)
        //   sw r, off($sp)
        // => drop the sw
        ArrayList<MipsInstr> pass10 = new ArrayList<>();
        int t = 0;
        while (t < Instrs.size()) {
            if (t + 1 < Instrs.size() && Instrs.get(t) instanceof LswInstr && Instrs.get(t + 1) instanceof LswInstr) {
                LswInstr lw = (LswInstr) Instrs.get(t);
                LswInstr sw = (LswInstr) Instrs.get(t + 1);
                if (!lw.isLabelMode() && !sw.isLabelMode()
                        && "lw".equals(lw.getOp()) && "sw".equals(sw.getOp())
                        && lw.getRt() == Register.SP && sw.getRt() == Register.SP
                        && lw.getImmediate() == sw.getImmediate()
                        && lw.getRs() == sw.getRs()) {
                    pass10.add(lw);
                    t += 2;
                    continue;
                }
            }
            pass10.add(Instrs.get(t));
            t++;
        }
        Instrs = pass10;

        // Pass 11: remove redundant reload after store to same stack slot
        // Pattern:
        //   sw r, off($sp)
        //   lw r, off($sp)
        // => drop the lw
        ArrayList<MipsInstr> pass11 = new ArrayList<>();
        int u = 0;
        while (u < Instrs.size()) {
            if (u + 1 < Instrs.size() && Instrs.get(u) instanceof LswInstr && Instrs.get(u + 1) instanceof LswInstr) {
                LswInstr sw = (LswInstr) Instrs.get(u);
                LswInstr lw = (LswInstr) Instrs.get(u + 1);
                if (!sw.isLabelMode() && !lw.isLabelMode()
                        && "sw".equals(sw.getOp()) && "lw".equals(lw.getOp())
                        && sw.getRt() == Register.SP && lw.getRt() == Register.SP
                        && sw.getImmediate() == lw.getImmediate()
                        && sw.getRs() == lw.getRs()) {
                    pass11.add(sw);
                    u += 2;
                    continue;
                }
            }
            pass11.add(Instrs.get(u));
            u++;
        }
        Instrs = pass11;

        // Pass 12: remove duplicate consecutive loads of same stack slot into same register
        // Pattern:
        //   lw r, off($sp)
        //   lw r, off($sp)
        // => drop the second lw
        ArrayList<MipsInstr> pass12 = new ArrayList<>();
        int v = 0;
        while (v < Instrs.size()) {
            if (v + 1 < Instrs.size() && Instrs.get(v) instanceof LswInstr && Instrs.get(v + 1) instanceof LswInstr) {
                LswInstr lw1 = (LswInstr) Instrs.get(v);
                LswInstr lw2 = (LswInstr) Instrs.get(v + 1);
                if (!lw1.isLabelMode() && !lw2.isLabelMode()
                        && "lw".equals(lw1.getOp()) && "lw".equals(lw2.getOp())
                        && lw1.getRt() == Register.SP && lw2.getRt() == Register.SP
                        && lw1.getImmediate() == lw2.getImmediate()
                        && lw1.getRs() == lw2.getRs()) {
                    pass12.add(lw1);
                    v += 2;
                    continue;
                }
            }
            pass12.add(Instrs.get(v));
            v++;
        }
        Instrs = pass12;
    }

    /**
     * Conservative, basic-block-local liveness check.
     * Returns true if {@code reg} is used before it is redefined, or before the block ends.
     *
     * We treat labels, jumps, branches, and syscalls as block boundaries.
     */
    private boolean isUsedBeforeRedefOrBlockEnd(ArrayList<MipsInstr> list, int startIdx, Register reg) {
        if (reg == null) {
            return false;
        }
        for (int i = startIdx; i < list.size(); i++) {
            MipsInstr ins = list.get(i);

            // Basic block boundary: be conservative and assume live-out.
            if (ins instanceof Label || ins instanceof Syscall || ins instanceof JInstr) {
                return true;
            }
            if (ins instanceof IInstr) {
                IInstr ii = (IInstr) ins;
                if (ii.getRt() == null) {
                    // conditional branch ends the block
                    return true;
                }
            }

            // If used before a redefinition, it's live.
            if (usesRegister(ins, reg)) {
                return true;
            }

            // If redefined, and not used so far, it's dead along this linear scan.
            Register def = getDefinedRegister(ins);
            if (def == reg) {
                return false;
            }
        }
        // End of instruction stream: be conservative.
        return true;
    }

    /** Returns true if the instruction reads {@code reg} as an input. */
    private boolean usesRegister(MipsInstr instr, Register reg) {
        if (instr == null || reg == null) {
            return false;
        }
        if (instr instanceof MoveInstr) {
            return ((MoveInstr) instr).getFrom() == reg;
        }
        if (instr instanceof LswInstr) {
            LswInstr m = (LswInstr) instr;
            if (m.isLabelMode()) {
                // lw/sw label mode doesn't use a base register.
                // For lw: reads none; for sw: reads rs.
                return "sw".equals(m.getOp()) && m.getRs() == reg;
            }
            if ("lw".equals(m.getOp())) {
                // base register is used
                return m.getRt() == reg;
            }
            if ("sw".equals(m.getOp())) {
                // source and base are used
                return m.getRs() == reg || m.getRt() == reg;
            }
            return false;
        }
        if (instr instanceof IInstr) {
            IInstr i = (IInstr) instr;
            if (i.getRt() == null) {
                // beq/bne: uses rs
                return i.getRs() == reg;
            }
            // arithmetic/shift: uses rt
            return i.getRt() == reg;
        }
        if (instr instanceof RInstr) {
            RInstr r = (RInstr) instr;
            return r.getLeft() == reg || r.getRight() == reg;
        }
        if (instr instanceof SpecialInstr) {
            // mfhi/mflo: no input register; treat as no uses
            return false;
        }
        if (instr instanceof LiInstr || instr instanceof LaInstr) {
            return false;
        }
        return false;
    }

    private boolean isSameSpSlot(LswInstr a, LswInstr b) {
        if (a.isLabelMode() || b.isLabelMode()) {
            return false;
        }
        if (a.getRt() != Register.SP || b.getRt() != Register.SP) {
            return false;
        }
        int offA = a.getImmediate();
        int offB = b.getImmediate();
        if (offA != offB) {
            return false;
        }
        // Avoid touching saved $ra slot.
        if (offA == 0) {
            return false;
        }
        // Only word-aligned stack accesses.
        return (offA % 4) == 0;
    }

    private boolean isEligibleSpSlot(LswInstr a) {
        if (a.isLabelMode()) {
            return false;
        }
        if (a.getRt() != Register.SP) {
            return false;
        }
        int off = a.getImmediate();
        if (off == 0) {
            return false;
        }
        return (off % 4) == 0;
    }

    private static class SlotState {
        Register knownReg = null;
        Register lastStoredReg = null;
        int lastStoreOutIndex = -1;
        boolean readSinceLastStore = true;
    }

    private void clearSlotStates(Map<Integer, SlotState> slotStates) {
        slotStates.clear();
    }

    private void invalidateByDefinedReg(Map<Integer, SlotState> slotStates, Register defined) {
        for (SlotState st : slotStates.values()) {
            if (st.knownReg == defined) {
                st.knownReg = null;
            }
            if (st.lastStoredReg == defined) {
                st.lastStoredReg = null;
            }
        }
    }

    private Register getDefinedRegister(MipsInstr instr) {
        if (instr instanceof LswInstr) {
            LswInstr m = (LswInstr) instr;
            if (m.getOp().equals("lw")) {
                return m.getRs();
            }
            return null;
        }
        if (instr instanceof MoveInstr) {
            return ((MoveInstr) instr).getTo();
        }
        if (instr instanceof LiInstr) {
            return ((LiInstr) instr).getTo();
        }
        if (instr instanceof LaInstr) {
            return ((LaInstr) instr).getTo();
        }
        if (instr instanceof IInstr) {
            IInstr i = (IInstr) instr;
            // Most I-type ops define the first printed register.
            if (i.getRt() == null) {
                return null;
            }
            return i.getRs();
        }
        if (instr instanceof RInstr) {
            Register ans = ((RInstr) instr).getAns();
            if (ans == Register.HI || ans == Register.LO) {
                return null;
            }
            return ans;
        }
        if (instr instanceof SpecialInstr) {
            return ((SpecialInstr) instr).getReg();
        }
        return null;
    }
}
