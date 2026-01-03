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

    private static final int PEEPHOLE_MAX_ROUNDS = 4;

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
    peepholeOptimizeToFixpoint();

        for (MipsInstr instr : Instrs) {
            optimizedModule.addInstr(instr);
        }

        return optimizedModule;
    }

    private void peepholeOptimizeToFixpoint() {
        long prevSig = instrStreamSignature(Instrs);
        for (int round = 0; round < PEEPHOLE_MAX_ROUNDS; round++) {
            peepholeOptimize();
            long sig = instrStreamSignature(Instrs);
            if (sig == prevSig) {
                break;
            }
            prevSig = sig;
        }
    }

    /**
     * Compute a compact, deterministic signature for the current instruction stream.
     *
     * Used to stop multi-round peephole optimization early when nothing changes.
     */
    private long instrStreamSignature(ArrayList<MipsInstr> list) {
        long h = 0xcbf29ce484222325L; // FNV-1a 64-bit offset basis
        h = fnvMix(h, list == null ? 0 : list.size());
        if (list == null) {
            return h;
        }
        for (MipsInstr ins : list) {
            if (ins == null) {
                h = fnvMix(h, 0x9e3779b9);
                continue;
            }
            h = fnvMix(h, instrTypeId(ins));
            h = fnvMix(h, ins.getOp());

            if (ins instanceof Label) {
                h = fnvMix(h, ((Label) ins).getName());
            }
            else if (ins instanceof JInstr) {
                JInstr j = (JInstr) ins;
                h = fnvMix(h, j.getLabel());
                h = fnvMix(h, j.getReg() == null ? -1 : j.getReg().ordinal());
            }
            else if (ins instanceof IInstr) {
                IInstr i = (IInstr) ins;
                h = fnvMix(h, i.getRs() == null ? -1 : i.getRs().ordinal());
                h = fnvMix(h, i.getRt() == null ? -1 : i.getRt().ordinal());
                h = fnvMix(h, i.getImmediate());
                h = fnvMix(h, i.getLabel());
            }
            else if (ins instanceof LswInstr) {
                LswInstr m = (LswInstr) ins;
                h = fnvMix(h, m.getRs() == null ? -1 : m.getRs().ordinal());
                h = fnvMix(h, m.getRt() == null ? -1 : m.getRt().ordinal());
                h = fnvMix(h, m.getImmediate());
                h = fnvMix(h, m.getLabel());
            }
            else if (ins instanceof RInstr) {
                RInstr r = (RInstr) ins;
                h = fnvMix(h, r.getAns() == null ? -1 : r.getAns().ordinal());
                h = fnvMix(h, r.getLeft() == null ? -1 : r.getLeft().ordinal());
                h = fnvMix(h, r.getRight() == null ? -1 : r.getRight().ordinal());
            }
            else if (ins instanceof SpecialInstr) {
                SpecialInstr s = (SpecialInstr) ins;
                h = fnvMix(h, s.getReg() == null ? -1 : s.getReg().ordinal());
            }
            else if (ins instanceof MoveInstr) {
                MoveInstr mv = (MoveInstr) ins;
                h = fnvMix(h, mv.getFrom() == null ? -1 : mv.getFrom().ordinal());
                h = fnvMix(h, mv.getTo() == null ? -1 : mv.getTo().ordinal());
            }
            else if (ins instanceof LiInstr) {
                LiInstr li = (LiInstr) ins;
                h = fnvMix(h, li.getTo() == null ? -1 : li.getTo().ordinal());
                h = fnvMix(h, li.getImmediate());
            }
            else if (ins instanceof LaInstr) {
                LaInstr la = (LaInstr) ins;
                h = fnvMix(h, la.getTo() == null ? -1 : la.getTo().ordinal());
                h = fnvMix(h, la.getAddr());
            }
        }
        return h;
    }

    /**
     * Small stable type id for signature hashing.
     * Avoids expensive per-instruction class-name hashing.
     */
    private int instrTypeId(MipsInstr ins) {
        if (ins instanceof Label) return 1;
        if (ins instanceof JInstr) return 2;
        if (ins instanceof IInstr) return 3;
        if (ins instanceof LswInstr) return 4;
        if (ins instanceof RInstr) return 5;
        if (ins instanceof SpecialInstr) return 6;
        if (ins instanceof Syscall) return 7;
        if (ins instanceof MoveInstr) return 8;
        if (ins instanceof LiInstr) return 9;
        if (ins instanceof LaInstr) return 10;
        return 31;
    }

    private long fnvMix(long h, int v) {
        long x = v;
        h ^= (x & 0xff);
        h *= 0x100000001b3L;
        h ^= ((x >>> 8) & 0xff);
        h *= 0x100000001b3L;
        h ^= ((x >>> 16) & 0xff);
        h *= 0x100000001b3L;
        h ^= ((x >>> 24) & 0xff);
        h *= 0x100000001b3L;
        return h;
    }

    private long fnvMix(long h, String s) {
        if (s == null) {
            return fnvMix(h, 0);
        }
        // Use cached String.hashCode() for speed; we only need a stable signature, not a cryptographic hash.
        return fnvMix(h, s.hashCode());
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
        // Track simple constant values held in registers within the current basic block.
        Map<Register, Integer> regConst = new HashMap<>();

        for (MipsInstr instr : Instrs) {
            // Basic block boundary: label.
            if (instr instanceof Label) {
                clearSlotStates(slotStates);
                regConst.clear();
                out.add(instr);
                continue;
            }

            // Hard barriers.
            if (instr instanceof Syscall) {
                clearSlotStates(slotStates);
                regConst.clear();
                out.add(instr);
                clearSlotStates(slotStates);
                regConst.clear();
                continue;
            }
            if (instr instanceof JInstr) {
                clearSlotStates(slotStates);
                regConst.clear();
                out.add(instr);
                clearSlotStates(slotStates);
                regConst.clear();
                continue;
            }
            if (instr instanceof IInstr) {
                IInstr iInstr = (IInstr) instr;
                // Branch-like IInstr (beq/bne/...) ends the block.
                if (iInstr.getRt() == null) {
                    clearSlotStates(slotStates);
                    regConst.clear();
                    out.add(instr);
                    clearSlotStates(slotStates);
                    regConst.clear();
                    continue;
                }
                // Stack pointer adjustment changes addressing for all $sp slots.
                if (iInstr.getRs() == Register.SP) {
                    clearSlotStates(slotStates);
                    regConst.clear();
                    out.add(instr);
                    clearSlotStates(slotStates);
                    regConst.clear();
                    continue;
                }
            }

            // Track register constants for li/move (block-local).
            if (instr instanceof LiInstr) {
                LiInstr li = (LiInstr) instr;
                regConst.put(li.getTo(), li.getImmediate());
                // li defines the register, so invalidate any slot caches depending on that reg.
                invalidateByDefinedReg(slotStates, li.getTo());
                out.add(instr);
                continue;
            }
            if (instr instanceof MoveInstr) {
                MoveInstr mv = (MoveInstr) instr;
                Register from = mv.getFrom();
                Register to = mv.getTo();
                if (regConst.containsKey(from)) {
                    regConst.put(to, regConst.get(from));
                } else {
                    regConst.remove(to);
                }
                invalidateByDefinedReg(slotStates, to);
                out.add(instr);
                continue;
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

                    // If we know this stack slot holds a constant, replace load with li.
                    if (state.knownConst != null) {
                        Integer already = regConst.get(dst);
                        if (already != null && already.equals(state.knownConst)) {
                            // Redundant: dst already holds the same constant.
                            state.readSinceLastStore = true;
                            state.knownReg = dst;
                            continue;
                        }
                        out.add(new LiInstr(dst, state.knownConst, false));
                        regConst.put(dst, state.knownConst);
                        state.readSinceLastStore = true;
                        invalidateByDefinedReg(slotStates, dst);
                        state.knownReg = dst;
                        continue;
                    }

                    Register src = state.knownReg;
                    if (src == null) {
                        src = state.lastStoredReg;
                    }

                    if (src != null) {
                        // Replace lw with move (or delete if dst already equals src).
                        if (dst != src) {
                            out.add(new MoveInstr(src, dst, false));
                        }
                        // Propagate constant info through the move.
                        if (regConst.containsKey(src)) {
                            regConst.put(dst, regConst.get(src));
                        } else {
                            regConst.remove(dst);
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
                    regConst.remove(dst);
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
                    // If src holds a known constant, remember it for this stack slot.
                    if (regConst.containsKey(src)) {
                        state.knownConst = regConst.get(src);
                    } else {
                        state.knownConst = null;
                    }
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
                regConst.remove(defined);
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

        // Pass 13: merge duplicate div for / and % of same operands
        // Pattern:
        //   div a, b
        //   mflo q
        //   div a, b
        //   mfhi r
        // =>
        //   div a, b
        //   mflo q
        //   mfhi r
        // and similarly with mfhi/mflo swapped.
        ArrayList<MipsInstr> pass13 = new ArrayList<>();
        int w = 0;
        while (w < Instrs.size()) {
            if (w + 3 < Instrs.size()
                    && Instrs.get(w) instanceof RInstr
                    && Instrs.get(w + 1) instanceof SpecialInstr
                    && Instrs.get(w + 2) instanceof RInstr
                    && Instrs.get(w + 3) instanceof SpecialInstr) {
                RInstr d1 = (RInstr) Instrs.get(w);
                SpecialInstr m1 = (SpecialInstr) Instrs.get(w + 1);
                RInstr d2 = (RInstr) Instrs.get(w + 2);
                SpecialInstr m2 = (SpecialInstr) Instrs.get(w + 3);

                boolean isDivPair = "div".equals(d1.getOp()) && "div".equals(d2.getOp())
                        && d1.getLeft() == d2.getLeft() && d1.getRight() == d2.getRight();
                if (isDivPair) {
                    String o1 = m1.getOp();
                    String o2 = m2.getOp();
                    boolean isLoHi = ("mflo".equals(o1) && "mfhi".equals(o2));
                    boolean isHiLo = ("mfhi".equals(o1) && "mflo".equals(o2));
                    if (isLoHi || isHiLo) {
                        pass13.add(d1);
                        pass13.add(m1);
                        pass13.add(m2);
                        w += 4;
                        continue;
                    }
                }
            }
            pass13.add(Instrs.get(w));
            w++;
        }
        Instrs = pass13;

        // Pass 14: remove dead moves within a basic block
        // Pattern:
        //   move rD, rS
        //   ... (no use of rD before redef / block end)
        // => remove the move
        ArrayList<MipsInstr> pass14 = new ArrayList<>();
        for (int idx = 0; idx < Instrs.size(); idx++) {
            MipsInstr ins = Instrs.get(idx);
            if (ins instanceof MoveInstr) {
                MoveInstr mv = (MoveInstr) ins;
                Register dst = mv.getTo();
                // Keep only if destination is used later in the block.
                boolean live = isUsedBeforeRedefOrBlockEnd(Instrs, idx + 1, dst);
                if (!live) {
                    continue;
                }
            }
            pass14.add(ins);
        }
        Instrs = pass14;

        // Pass 15: fold a move into the following arithmetic instruction
        // Patterns:
        //   move rT, rS
        //   addi rD, rT, imm    => addi rD, rS, imm
        //   addu rD, rT, rX    => addu rD, rS, rX   (and similarly for right operand)
        // Only delete the move if rT is dead afterwards (block-local).
        ArrayList<MipsInstr> pass15 = new ArrayList<>();
        int z = 0;
        while (z < Instrs.size()) {
            MipsInstr cur = Instrs.get(z);
            if (cur instanceof MoveInstr && z + 1 < Instrs.size()) {
                MoveInstr mv = (MoveInstr) cur;
                Register from = mv.getFrom();
                Register to = mv.getTo();
                MipsInstr nxt = Instrs.get(z + 1);

                boolean canDeleteMove = !isUsedBeforeRedefOrBlockEnd(Instrs, z + 2, to);

                // Fold into I-type arithmetic/shift: op rs rt imm (rt is input)
                if (nxt instanceof IInstr) {
                    IInstr ii = (IInstr) nxt;
                    if (ii.getRt() != null) {
                        // Avoid rewriting stack pointer adjustments.
                        if (!(ii.getRs() == Register.SP && ii.getRt() == Register.SP)) {
                            Register newRt = ii.getRt();
                            if (newRt == to) {
                                newRt = from;
                            }
                            if (newRt != ii.getRt()) {
                                if (!canDeleteMove) {
                                    pass15.add(cur);
                                }
                                pass15.add(new IInstr(ii.getOp(), ii.getRs(), newRt, ii.getImmediate(), false));
                                z += 2;
                                continue;
                            }
                        }
                    }
                }

                // Fold into R-type: op ans left right
                if (nxt instanceof RInstr) {
                    RInstr rr = (RInstr) nxt;
                    Register newL = rr.getLeft();
                    Register newR = rr.getRight();
                    if (newL == to) {
                        newL = from;
                    }
                    if (newR == to) {
                        newR = from;
                    }
                    if (newL != rr.getLeft() || newR != rr.getRight()) {
                        if (!canDeleteMove) {
                            pass15.add(cur);
                        }
                        pass15.add(new RInstr(rr.getOp(), rr.getAns(), newL, newR, false));
                        z += 2;
                        continue;
                    }
                }
            }

            pass15.add(cur);
            z++;
        }
        Instrs = pass15;

        // Pass 16: jump threading (branch/jump to jump-only label)
        // If a label's first real instruction is `j L2`, rewrite all `j/branch -> L1` into `-> L2`.
        // This reduces JUMP/BRANCH count without affecting semantics.
        HashMap<String, String> alias = new HashMap<>();
        HashMap<String, Integer> labelIndex = new HashMap<>();
        for (int idx = 0; idx < Instrs.size(); idx++) {
            MipsInstr ins = Instrs.get(idx);
            if (ins instanceof Label) {
                labelIndex.put(((Label) ins).getName(), idx);
            }
        }

        java.util.function.IntUnaryOperator nextNonLabel = (start) -> {
            int i2 = start;
            while (i2 < Instrs.size() && (Instrs.get(i2) instanceof Label)) {
                i2++;
            }
            return i2;
        };

        for (Map.Entry<String, Integer> e : labelIndex.entrySet()) {
            String l1 = e.getKey();
            int li = e.getValue();
            int jIdx = nextNonLabel.applyAsInt(li + 1);
            if (jIdx >= Instrs.size()) {
                continue;
            }
            MipsInstr first = Instrs.get(jIdx);
            if (first instanceof JInstr) {
                JInstr jmpFirst = (JInstr) first;
                if ("j".equals(jmpFirst.getOp()) && jmpFirst.getLabel() != null && !jmpFirst.getLabel().isEmpty()) {
                    alias.put(l1, jmpFirst.getLabel());
                }
            }
        }

        java.util.function.Function<String, String> resolve = new java.util.function.Function<String, String>() {
            @Override
            public String apply(String s) {
                if (s == null) {
                    return null;
                }
                String cur = s;
                int guard = 0;
                while (alias.containsKey(cur) && guard++ < 32) {
                    String nxt = alias.get(cur);
                    if (nxt == null || nxt.equals(cur)) {
                        break;
                    }
                    cur = nxt;
                }
                // Path compression for the original.
                if (!cur.equals(s)) {
                    alias.put(s, cur);
                }
                return cur;
            }
        };

        ArrayList<MipsInstr> pass16 = new ArrayList<>();
        for (MipsInstr ins : Instrs) {
            if (ins instanceof JInstr) {
                JInstr jmp = (JInstr) ins;
                if ("j".equals(jmp.getOp()) && jmp.getLabel() != null && !jmp.getLabel().isEmpty()) {
                    String tgt = resolve.apply(jmp.getLabel());
                    if (tgt != null && !tgt.equals(jmp.getLabel())) {
                        pass16.add(new JInstr("j", tgt, false));
                        continue;
                    }
                }
            }
            if (ins instanceof IInstr) {
                IInstr br = (IInstr) ins;
                if (br.getRt() == null && br.getLabel() != null && !br.getLabel().isEmpty()) {
                    String tgt = resolve.apply(br.getLabel());
                    if (tgt != null && !tgt.equals(br.getLabel())) {
                        pass16.add(new IInstr(br.getOp(), br.getRs(), br.getImmediate(), tgt, false));
                        continue;
                    }
                }
            }
            pass16.add(ins);
        }
        Instrs = pass16;

        // Pass 17: re-run j-to-fallthrough removal after retargeting
        ArrayList<MipsInstr> pass17 = new ArrayList<>();
        int rr = 0;
        while (rr < Instrs.size()) {
            MipsInstr now = Instrs.get(rr);
            if (rr < Instrs.size() - 1) {
                MipsInstr nxt = Instrs.get(rr + 1);
                if (now instanceof JInstr && nxt instanceof Label) {
                    JInstr jmp = (JInstr) now;
                    Label l = (Label) nxt;
                    if ("j".equals(jmp.getOp()) && jmp.getLabel() != null && jmp.getLabel().equals(l.getName())) {
                        rr++;
                        continue;
                    }
                }
            }
            pass17.add(now);
            rr++;
        }
        Instrs = pass17;

        // Pass 18: eliminate adjacent overwritten pure register-def instructions
        // Pattern:
        //   <pure def r>
        //   <pure def r>   (and it does not read r)
        // => drop the first instruction
        // This is a cheap local DCE that cuts down many redundant moves/temps.
        ArrayList<MipsInstr> pass18 = new ArrayList<>();
        int a18 = 0;
        while (a18 < Instrs.size()) {
            MipsInstr cur = Instrs.get(a18);
            if (a18 + 1 < Instrs.size()) {
                MipsInstr nxt = Instrs.get(a18 + 1);
                Register defCur = getDefinedRegister(cur);
                Register defNxt = getDefinedRegister(nxt);
                if (defCur != null && defCur == defNxt
                        && isPureDefInstr(cur) && isPureDefInstr(nxt)
                        && !usesRegister(nxt, defCur)) {
                    // Drop cur.
                    a18++;
                    continue;
                }
            }
            pass18.add(cur);
            a18++;
        }
        Instrs = pass18;

        // Pass 19: fold li/la into following move when the temp is dead before any block boundary
        // Pattern:
        //   li t, imm
        //   move d, t
        // => li d, imm
        // (and similarly for la)
        // Only apply when t is not used before a block boundary/redefinition.
        ArrayList<MipsInstr> pass19 = new ArrayList<>();
        int b19 = 0;
        while (b19 < Instrs.size()) {
            MipsInstr cur = Instrs.get(b19);
            if (b19 + 1 < Instrs.size() && Instrs.get(b19 + 1) instanceof MoveInstr) {
                MoveInstr mv = (MoveInstr) Instrs.get(b19 + 1);

                if (cur instanceof LiInstr) {
                    LiInstr li = (LiInstr) cur;
                    Register tReg = li.getTo();
                    if (mv.getFrom() == tReg) {
                        boolean tDead = !isUsedBeforeRedefOrBlockEnd(Instrs, b19 + 2, tReg);
                        if (tDead && mv.getTo() != tReg) {
                            pass19.add(new LiInstr(mv.getTo(), li.getImmediate(), false));
                            b19 += 2;
                            continue;
                        }
                    }
                }

                if (cur instanceof LaInstr) {
                    LaInstr la = (LaInstr) cur;
                    Register tReg = la.getTo();
                    if (mv.getFrom() == tReg) {
                        boolean tDead = !isUsedBeforeRedefOrBlockEnd(Instrs, b19 + 2, tReg);
                        if (tDead && mv.getTo() != tReg) {
                            pass19.add(new LaInstr(mv.getTo(), la.getAddr(), false));
                            b19 += 2;
                            continue;
                        }
                    }
                }
            }

            pass19.add(cur);
            b19++;
        }
        Instrs = pass19;

        // Pass 20: remove conditional branch to immediate fallthrough label
        // Pattern:
        //   beq/bne r, imm, L
        // L:
        // => drop the branch (both paths already fall through to L)
        ArrayList<MipsInstr> pass20 = new ArrayList<>();
        int c20 = 0;
        while (c20 < Instrs.size()) {
            MipsInstr cur = Instrs.get(c20);
            if (cur instanceof IInstr && c20 + 1 < Instrs.size() && Instrs.get(c20 + 1) instanceof Label) {
                IInstr br = (IInstr) cur;
                Label l = (Label) Instrs.get(c20 + 1);
                boolean isBr = br.getRt() == null && ("beq".equals(br.getOp()) || "bne".equals(br.getOp()));
                if (isBr && br.getLabel() != null && br.getLabel().equals(l.getName())) {
                    // Drop the redundant branch.
                    c20++;
                    continue;
                }
            }
            pass20.add(cur);
            c20++;
        }
        Instrs = pass20;

        // Pass 21: collapse adjacent move chains
        // Pattern:
        //   move t, s
        //   move d, t
        // => move d, s   (and drop the first move if t is dead afterwards)
        ArrayList<MipsInstr> pass21 = new ArrayList<>();
        int d21 = 0;
        while (d21 < Instrs.size()) {
            MipsInstr cur = Instrs.get(d21);
            if (cur instanceof MoveInstr && d21 + 1 < Instrs.size() && Instrs.get(d21 + 1) instanceof MoveInstr) {
                MoveInstr m1 = (MoveInstr) cur;
                MoveInstr m2 = (MoveInstr) Instrs.get(d21 + 1);
                Register tmpReg = m1.getTo();
                Register srcReg = m1.getFrom();
                if (m2.getFrom() == tmpReg) {
                    boolean tmpDead = !isUsedBeforeRedefOrBlockEnd(Instrs, d21 + 2, tmpReg);
                    if (!tmpDead) {
                        pass21.add(cur);
                    }
                    pass21.add(new MoveInstr(srcReg, m2.getTo(), false));
                    d21 += 2;
                    continue;
                }
            }
            pass21.add(cur);
            d21++;
        }
        Instrs = pass21;

        // Pass 22: fold constant conditional branches after li
        // Pattern:
        //   li r, k
        //   beq/bne r, imm, L
        // => either drop branch (never taken) or turn into unconditional j (always taken)
        ArrayList<MipsInstr> pass22 = new ArrayList<>();
        int e22 = 0;
        while (e22 < Instrs.size()) {
            MipsInstr cur = Instrs.get(e22);
            if (cur instanceof LiInstr && e22 + 1 < Instrs.size() && Instrs.get(e22 + 1) instanceof IInstr) {
                LiInstr li = (LiInstr) cur;
                IInstr br = (IInstr) Instrs.get(e22 + 1);
                boolean isBr = br.getRt() == null && ("beq".equals(br.getOp()) || "bne".equals(br.getOp()));
                if (isBr && br.getRs() == li.getTo() && br.getLabel() != null && !br.getLabel().isEmpty()) {
                    boolean eq = (li.getImmediate() == br.getImmediate());
                    boolean taken = ("beq".equals(br.getOp()) && eq) || ("bne".equals(br.getOp()) && !eq);
                    // Keep li (it may be used later).
                    pass22.add(cur);
                    if (taken) {
                        pass22.add(new JInstr("j", br.getLabel(), false));
                    }
                    // else: drop the branch
                    e22 += 2;
                    continue;
                }
            }
            pass22.add(cur);
            e22++;
        }
        Instrs = pass22;

        // Pass 23: branch followed by j to the same label => unconditional j
        // Pattern:
        //   beq/bne r, imm, L
        //   j L
        // => j L
        ArrayList<MipsInstr> pass23 = new ArrayList<>();
        int f23 = 0;
        while (f23 < Instrs.size()) {
            if (f23 + 1 < Instrs.size() && Instrs.get(f23) instanceof IInstr && Instrs.get(f23 + 1) instanceof JInstr) {
                IInstr br = (IInstr) Instrs.get(f23);
                JInstr jmp = (JInstr) Instrs.get(f23 + 1);
                boolean isBr = br.getRt() == null && ("beq".equals(br.getOp()) || "bne".equals(br.getOp()));
                boolean isJ = "j".equals(jmp.getOp()) && jmp.getLabel() != null && !jmp.getLabel().isEmpty();
                if (isBr && isJ && br.getLabel() != null && br.getLabel().equals(jmp.getLabel())) {
                    pass23.add(jmp);
                    f23 += 2;
                    continue;
                }
            }
            pass23.add(Instrs.get(f23));
            f23++;
        }
        Instrs = pass23;
    }

    /**
     * Returns true if the instruction is a pure register definition without side effects.
     * Used for local DCE/overwrite elimination.
     */
    private boolean isPureDefInstr(MipsInstr instr) {
        if (instr == null) {
            return false;
        }
        Register def = getDefinedRegister(instr);
        if (def == null) {
            return false;
        }
        // Never treat stack pointer / return address updates as removable.
        if (def == Register.SP || def == Register.RA) {
            return false;
        }
        if (instr instanceof Label || instr instanceof Syscall || instr instanceof JInstr) {
            return false;
        }
        if (instr instanceof LswInstr) {
            return false;
        }
        if (instr instanceof IInstr) {
            IInstr ii = (IInstr) instr;
            // Branch-like IInstr has rt==null (control flow), not removable.
            if (ii.getRt() == null) {
                return false;
            }
            // Be extra conservative around SP adjustments.
            if (ii.getRs() == Register.SP) {
                return false;
            }
        }
        // Move/Li/La/normal arithmetic/Special are considered pure.
        return (instr instanceof MoveInstr)
                || (instr instanceof LiInstr)
                || (instr instanceof LaInstr)
                || (instr instanceof IInstr)
                || (instr instanceof RInstr)
                || (instr instanceof SpecialInstr);
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
        Integer knownConst = null;
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
