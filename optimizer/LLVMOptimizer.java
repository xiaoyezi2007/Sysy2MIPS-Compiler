package optimizer;

import llvm.BasicBlock;
import llvm.Function;
import llvm.GlobalValue;
import llvm.IrModule;
import llvm.GlobalVariable;
import llvm.IRType;
import llvm.User;
import llvm.Use;
import llvm.Value;
import llvm.ValueType;
import llvm.constant.Constant;
import llvm.constant.ConstantInt;
import llvm.GlobalVariable;
import llvm.instr.AllocaInstr;
import llvm.instr.AluInstr;
import llvm.instr.BranchInstr;
import llvm.instr.CmpInstr;
import llvm.instr.GepInstr;
import llvm.instr.Instruction;
import llvm.instr.CallInstr;
import llvm.instr.JumpInstr;
import llvm.instr.LoadInstr;
import llvm.instr.PhiInstr;
import llvm.instr.RetInstr;
import llvm.instr.StoreInstr;
import llvm.instr.ZextInstr;
import util.Tool;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class LLVMOptimizer {
    private Tool tool = new Tool();

    private IrModule irModule;
    private ArrayList<Function> functions;
    private ArrayList<BasicBlock> basicBlocks;
    private ArrayList<Instruction> instructions;
    private ArrayList<GlobalValue> globals;

    private static HashMap<GepInstr, Integer> gepCnt = new HashMap<>();

    // Limits for constant-call evaluation to stay safe.
    private static final int CONST_EVAL_MAX_DEPTH = 64;
    private static final int CONST_EVAL_MAX_STEPS = 200000;

    private HashMap<String,Value> gvn = new HashMap<>();

    /**
     * Basic induction-variable (IV) info recognized from SSA form.
     * Keyed by the header phi instruction.
     */
    private final HashMap<PhiInstr, InductionVarInfo> inductionVars = new HashMap<>();

    // Loop metadata keyed by header block.
    private final HashMap<BasicBlock, LoopInfo> loopsByHeader = new HashMap<>();

    // Naming for optimizer-created temporaries (do not depend on Builder global counter).
    private int lsrNameCounter = 0;

    private static class InductionVarInfo {
        final PhiInstr phi;
        final Value initValue;      // incoming value from outside the loop
        final int step;             // constant step per iteration (can be negative)
        final BasicBlock header;
        final HashSet<BasicBlock> latches;
        final HashSet<BasicBlock> loopBlocks;

        // One representative update value from inside the loop (typically an AluInstr chain root).
        final Value updateValue;

        InductionVarInfo(PhiInstr phi, Value initValue, int step, Value updateValue,
                         BasicBlock header, HashSet<BasicBlock> latches, HashSet<BasicBlock> loopBlocks) {
            this.phi = phi;
            this.initValue = initValue;
            this.step = step;
            this.updateValue = updateValue;
            this.header = header;
            this.latches = latches;
            this.loopBlocks = loopBlocks;
        }
    }

    private static class LoopInfo {
        final BasicBlock header;
        final HashSet<BasicBlock> blocks = new HashSet<>();
        final HashSet<BasicBlock> latches = new HashSet<>();
        final HashSet<BasicBlock> outsidePreds = new HashSet<>();

        LoopInfo(BasicBlock header) {
            this.header = header;
        }
    }

    // Function purity (side-effect) analysis result: true means referentially transparent.
    private final HashMap<Function, Boolean> pureFunc = new HashMap<>();
    private boolean purityComputed = false;

    public LLVMOptimizer(IrModule irModule) {
        this.irModule = irModule;
    }

    public IrModule optimize() {
        gepCnt.clear();
        buildCFG();

        TailCall2Loop();

        FuncInline();

        initDominate();
        initDF();
        insertPhi();
        renameSSA();

        // Analysis only: recognize basic induction variables in loops (SSA).
        InductionVarAnalyze();

        // Transform: loop strength reduction based on recognized induction variables.
        LoopStrengthReduce();

        SimplifyIcmp();

        DeadArgEliminate();

        DeadBranchEliminate();

        RemoveJumpOnlyBlocks();

        DeadLoopEliminate();

        DeadRetEliminate();

        RemoveBlocks();

        LocalArrayLift();

        PickGep();

        SoraPass();

        FoldConstLocalArray();

        ConstIdx2Value();

        DeadLocalArrayEliminate();

        calGepCnt();
        deadCodeCheck();

        analyzeFunctionPurity();

        initDomTree();
        rename();
        GVN();
        GCM();
        InstructionResort();

        deadCodeCheck();

        liveAnalyse();

        DeadStoreEliminate();

        CopyPropagation();

        rmKeyEdge();
        rmPhi();
        rename();

        rmSimpleEdge();

        RemoveUnusedFunctions();


        return irModule;
    }

    /**
     * LoopStrengthReduce: reduce strength of loop computations derived from basic IVs.
     *
     * Currently implemented (conservative):
     * - For a basic IV i with constant step, rewrite repeated "i * C" inside the loop into a derived IV:
     *     m = phi(outside: init*C, inside: m + step*C)
     *   and replace uses of the original multiply with m.
     *
     * Safety notes:
     * - Only operates on natural loops recorded in loopsByHeader.
     * - Only handles ConstantInt multipliers.
     * - Does not attempt to remove the base IV or change loop control; it only replaces derived expressions.
     */
    public void LoopStrengthReduce() {
        if (loopsByHeader.isEmpty() || inductionVars.isEmpty()) {
            return;
        }

        // Group base IVs by their loop header.
        HashMap<BasicBlock, ArrayList<InductionVarInfo>> ivsByHeader = new HashMap<>();
        for (InductionVarInfo info : inductionVars.values()) {
            ivsByHeader.computeIfAbsent(info.header, k -> new ArrayList<>()).add(info);
        }

        for (LoopInfo loop : loopsByHeader.values()) {
            ArrayList<InductionVarInfo> baseIvs = ivsByHeader.get(loop.header);
            if (baseIvs == null || baseIvs.isEmpty()) {
                continue;
            }
            for (InductionVarInfo base : baseIvs) {
                // Prefer reducing affine expressions first, so the following mul-only pass can
                // clean up any leftover multiplications that become dead.
                strengthReduceAffineFromMul(loop, base);
                strengthReduceMulByConst(loop, base);
            }
        }
    }

    private void strengthReduceMulByConst(LoopInfo loop, InductionVarInfo baseIv) {
        // Map multiplier -> derived phi.
        HashMap<Integer, PhiInstr> derivedForConst = new HashMap<>();

        // Pre-compute instruction -> parent block within this loop.
        HashMap<Instruction, BasicBlock> parentInLoop = new HashMap<>();
        for (BasicBlock b : loop.blocks) {
            for (Instruction ins : b.getInstructions()) {
                parentInLoop.put(ins, b);
            }
        }

        // Collect candidate mul instructions first to avoid concurrent modification.
        ArrayList<AluInstr> muls = new ArrayList<>();
        for (BasicBlock b : loop.blocks) {
            for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                if (!(ins instanceof AluInstr)) {
                    continue;
                }
                AluInstr alu = (AluInstr) ins;
                if (!"*".equals(alu.getOperator())) {
                    continue;
                }
                Value a = alu.getUseValue(0);
                Value c = alu.getUseValue(1);
                Integer ca = getConstInt(a);
                Integer cc = getConstInt(c);
                if (a == baseIv.phi && cc != null) {
                    muls.add(alu);
                }
                else if (c == baseIv.phi && ca != null) {
                    muls.add(alu);
                }
            }
        }
        if (muls.isEmpty()) {
            return;
        }

        for (AluInstr mul : muls) {
            // Determine multiplier.
            Value a = mul.getUseValue(0);
            Value b = mul.getUseValue(1);
            Integer k = null;
            if (a == baseIv.phi) {
                k = getConstInt(b);
            }
            else if (b == baseIv.phi) {
                k = getConstInt(a);
            }
            if (k == null) {
                continue;
            }

            PhiInstr derived = derivedForConst.get(k);
            if (derived == null) {
                derived = createDerivedMulInduction(loop, baseIv, k);
                if (derived == null) {
                    continue;
                }
                derivedForConst.put(k, derived);
            }

            // Replace mul uses with derived phi, but only inside the loop blocks.
            replaceAllUsesInLoop(mul, derived, parentInLoop);

            // Remove dead mul.
            if (mul.getUserList().isEmpty()) {
                BasicBlock owner = parentInLoop.get(mul);
                if (owner != null) {
                    removeInstrAndUnlink(owner, mul);
                }
            }
        }
    }

    private static class AffineKey {
        final Value base;
        final int scale;

        AffineKey(Value base, int scale) {
            this.base = base;
            this.scale = scale;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof AffineKey)) {
                return false;
            }
            AffineKey other = (AffineKey) o;
            return this.base == other.base && this.scale == other.scale;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(base) * 31 + scale;
        }
    }

    private void strengthReduceAffineFromMul(LoopInfo loop, InductionVarInfo baseIv) {
        // Build parent map for loop membership checks.
        HashMap<Instruction, BasicBlock> parentInLoop = new HashMap<>();
        for (BasicBlock b : loop.blocks) {
            for (Instruction ins : b.getInstructions()) {
                parentInLoop.put(ins, b);
            }
        }

        HashMap<AffineKey, PhiInstr> derived = new HashMap<>();
        ArrayList<AluInstr> candidates = new ArrayList<>();
        for (BasicBlock b : loop.blocks) {
            for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                if (!(ins instanceof AluInstr)) {
                    continue;
                }
                AluInstr alu = (AluInstr) ins;
                String op = alu.getOperator();
                if (!"+".equals(op) && !"-".equals(op)) {
                    continue;
                }
                candidates.add(alu);
            }
        }

        for (AluInstr add : candidates) {
            Value lhs = add.getUseValue(0);
            Value rhs = add.getUseValue(1);
            String op = add.getOperator();

            // Match invariant (+|-) (iv * C)
            Value base = null;
            Integer scale = null;

            // helper: check mul form
            Integer rhsMulC = getMulConstIfMatches(rhs, baseIv.phi);
            Integer lhsMulC = getMulConstIfMatches(lhs, baseIv.phi);

            if ("+".equals(op)) {
                if (rhsMulC != null && isLoopInvariant(lhs, parentInLoop)) {
                    base = lhs;
                    scale = rhsMulC;
                }
                else if (lhsMulC != null && isLoopInvariant(rhs, parentInLoop)) {
                    base = rhs;
                    scale = lhsMulC;
                }
            }
            else {
                // base - (iv*C)  ==> base + iv*(-C)
                if (rhsMulC != null && isLoopInvariant(lhs, parentInLoop)) {
                    base = lhs;
                    scale = -rhsMulC;
                }
            }

            if (base == null || scale == null) {
                continue;
            }

            // If scale is 0, expression is just base.
            if (scale == 0) {
                replaceAllUsesInLoop(add, base, parentInLoop);
                if (add.getUserList().isEmpty()) {
                    BasicBlock owner = parentInLoop.get(add);
                    if (owner != null) {
                        removeInstrAndUnlink(owner, add);
                    }
                }
                continue;
            }

            AffineKey key = new AffineKey(base, scale);
            PhiInstr phi = derived.get(key);
            if (phi == null) {
                phi = createDerivedAffineInduction(loop, baseIv, base, scale);
                if (phi == null) {
                    continue;
                }
                derived.put(key, phi);
            }

            replaceAllUsesInLoop(add, phi, parentInLoop);
            if (add.getUserList().isEmpty()) {
                BasicBlock owner = parentInLoop.get(add);
                if (owner != null) {
                    removeInstrAndUnlink(owner, add);
                }
            }
        }
    }

    private Integer getMulConstIfMatches(Value v, Value phi) {
        if (!(v instanceof AluInstr)) {
            return null;
        }
        AluInstr alu = (AluInstr) v;
        if (!"*".equals(alu.getOperator())) {
            return null;
        }
        Value a = alu.getUseValue(0);
        Value b = alu.getUseValue(1);
        if (a == phi) {
            return getConstInt(b);
        }
        if (b == phi) {
            return getConstInt(a);
        }
        return null;
    }

    private boolean isLoopInvariant(Value v, HashMap<Instruction, BasicBlock> parentInLoop) {
        if (v == null) {
            return false;
        }
        if (v instanceof ConstantInt) {
            return true;
        }
        if (v instanceof Instruction) {
            return !parentInLoop.containsKey((Instruction) v);
        }
        // Globals, args, allocas etc.
        return true;
    }

    private PhiInstr createDerivedAffineInduction(LoopInfo loop, InductionVarInfo baseIv, Value base, int scale) {
        if (loop.outsidePreds.isEmpty()) {
            return null;
        }
        long stepMulLong = (long) baseIv.step * (long) scale;
        int stepMul = (int) stepMulLong;

        PhiInstr derivedPhi = new PhiInstr(new IRType("i32"), loop.header, freshLsrName());

        // Insert after existing phis in header.
        ArrayList<Instruction> headerList = loop.header.getInstructions();
        int insertPos = 0;
        while (insertPos < headerList.size() && headerList.get(insertPos) instanceof PhiInstr) {
            insertPos++;
        }
        headerList.add(insertPos, derivedPhi);

        // Outside incoming: base + init*scale (computed in each outside pred).
        for (BasicBlock outside : loop.outsidePreds) {
            if (outside == null) {
                continue;
            }
            Value init = baseIv.initValue;
            Value initScaled;
            if (scale == 1) {
                initScaled = init;
            }
            else if (scale == -1) {
                initScaled = insertDetachedAluBeforeTerm(outside, new ConstantInt(0), "-", init);
            }
            else {
                initScaled = insertDetachedAluBeforeTerm(outside, init, "*", new ConstantInt(scale));
            }
            Value start;
            Integer z = getConstInt(initScaled);
            if (z != null && z == 0) {
                start = base;
            }
            else {
                start = insertDetachedAluBeforeTerm(outside, base, "+", initScaled);
            }
            derivedPhi.defBlock.add(outside);
            derivedPhi.addUseValue(start);
        }

        // Latch incoming: derived + stepMul.
        for (BasicBlock latch : loop.latches) {
            if (latch == null) {
                continue;
            }
            Value inVal;
            if (stepMul == 0) {
                inVal = derivedPhi;
            }
            else if (stepMul > 0) {
                inVal = insertDetachedAluBeforeTerm(latch, derivedPhi, "+", new ConstantInt(stepMul));
            }
            else {
                inVal = insertDetachedAluBeforeTerm(latch, derivedPhi, "-", new ConstantInt(-stepMul));
            }
            derivedPhi.defBlock.add(latch);
            derivedPhi.addUseValue(inVal);
        }

        return derivedPhi;
    }

    private void replaceAllUsesInLoop(Value from, Value to, HashMap<Instruction, BasicBlock> parentInLoop) {
        if (from == null || to == null || from == to) {
            return;
        }
        ArrayList<Value> users = new ArrayList<>(from.getUsers());
        for (Value uv : users) {
            if (!(uv instanceof User)) {
                continue;
            }
            if (uv instanceof Instruction) {
                if (!parentInLoop.containsKey((Instruction) uv)) {
                    continue;
                }
            }
            else {
                continue;
            }

            User u = (User) uv;
            boolean touched = false;
            for (Use use : u.getUseList()) {
                if (use.getValue() == from) {
                    use.setValue(to);
                    touched = true;
                }
            }
            if (!touched) {
                continue;
            }
            for (int i = 0; i < u.values.size(); i++) {
                if (u.values.get(i) == from) {
                    u.values.set(i, to);
                }
            }
            to.addUser(u);
            from.rmUser(u);
        }
    }

    private PhiInstr createDerivedMulInduction(LoopInfo loop, InductionVarInfo baseIv, int multiplier) {
        // Only handle simple preheader-like loops: we require at least one outside pred.
        if (loop.outsidePreds.isEmpty()) {
            return null;
        }
        // Avoid overflow in step computation, but keep Java int semantics consistent with ConstantInt.
        long stepMulLong = (long) baseIv.step * (long) multiplier;
        int stepMul = (int) stepMulLong;

        String name = freshLsrName();
        PhiInstr derivedPhi = new PhiInstr(new IRType("i32"), loop.header, name);

        // Insert derived phi right after existing phis.
        ArrayList<Instruction> headerList = loop.header.getInstructions();
        int insertPos = 0;
        while (insertPos < headerList.size() && headerList.get(insertPos) instanceof PhiInstr) {
            insertPos++;
        }
        headerList.add(insertPos, derivedPhi);

        // Outside incoming: init * multiplier (computed in each outside pred).
        for (BasicBlock outside : loop.outsidePreds) {
            if (outside == null) continue;
            Value init = baseIv.initValue;
            Value inVal;
            if (multiplier == 0) {
                inVal = new ConstantInt(0);
            }
            else if (multiplier == 1) {
                inVal = init;
            }
            else if (multiplier == -1) {
                // 0 - init
                inVal = insertDetachedAluBeforeTerm(outside, new ConstantInt(0), "-", init);
            }
            else {
                inVal = insertDetachedAluBeforeTerm(outside, init, "*", new ConstantInt(multiplier));
            }

            derivedPhi.defBlock.add(outside);
            derivedPhi.addUseValue(inVal);
        }

        // Inside incoming: derived + stepMul from each latch.
        for (BasicBlock latch : loop.latches) {
            if (latch == null) continue;
            Value inVal;
            if (stepMul == 0) {
                inVal = derivedPhi;
            }
            else if (stepMul > 0) {
                inVal = insertDetachedAluBeforeTerm(latch, derivedPhi, "+", new ConstantInt(stepMul));
            }
            else {
                inVal = insertDetachedAluBeforeTerm(latch, derivedPhi, "-", new ConstantInt(-stepMul));
            }
            derivedPhi.defBlock.add(latch);
            derivedPhi.addUseValue(inVal);
        }

        return derivedPhi;
    }

    private Value insertDetachedAluBeforeTerm(BasicBlock block, Value lhs, String op, Value rhs) {
        ArrayList<Instruction> list = block.getInstructions();
        int pos = Math.max(0, list.size() - 1);
        AluInstr alu = new AluInstr(lhs, op, rhs, freshLsrName(), false);
        list.add(pos, alu);
        return alu;
    }

    private String freshLsrName() {
        lsrNameCounter++;
        return "%lsr." + lsrNameCounter;
    }

    /**
     * InductionVarAnalyze: recognize basic induction variables.
     *
     * Recognized forms (analysis-only; no IR mutation):
     * - Natural loops via backedges (tail -> header where header dominates tail)
     * - Header phis that behave as a basic IV:
     *     i = phi(outside: init, inside: i (+|-) const)
     *   with optional chaining like (i + c1) + c2, and allowing multiple latches as long as
     *   all inside incoming edges use the same constant step.
     *
     * This pass is analysis-only and does not mutate IR.
     */
    public void InductionVarAnalyze() {
        inductionVars.clear();
        loopsByHeader.clear();

        // Needs dominator sets; ensure they're initialized.
        if (irModule == null) {
            return;
        }
        for (Function f : irModule.getFunctions()) {
            if (f.getBasicBlocks() == null || f.getBasicBlocks().isEmpty()) {
                continue;
            }
            HashMap<BasicBlock, LoopInfo> found = buildNaturalLoops(f);
            loopsByHeader.putAll(found);
            for (LoopInfo loop : found.values()) {
                recognizeInductionVarsInLoop(f, loop);
            }
        }
    }

    /**
     * Build natural loops in a function by enumerating backedges.
     * Multiple tails may map to the same header; we merge them.
     */
    private HashMap<BasicBlock, LoopInfo> buildNaturalLoops(Function f) {
        HashMap<BasicBlock, LoopInfo> res = new HashMap<>();
        for (BasicBlock tail : f.getBasicBlocks()) {
            for (BasicBlock succ : tail.next) {
                if (succ == null) continue;
                BasicBlock header = succ;
                if (!header.isDominate(tail)) {
                    continue;
                }

                HashSet<BasicBlock> blocks = collectLoop(tail, header);
                if (blocks == null || blocks.isEmpty()) {
                    continue;
                }

                LoopInfo info = res.get(header);
                if (info == null) {
                    info = new LoopInfo(header);
                    res.put(header, info);
                }
                info.blocks.addAll(blocks);
                info.latches.add(tail);
            }
        }

        for (LoopInfo loop : res.values()) {
            // Outside preds = header.prev - loop.blocks
            for (BasicBlock pred : loop.header.prev) {
                if (pred != null && !loop.blocks.contains(pred)) {
                    loop.outsidePreds.add(pred);
                }
            }
        }
        return res;
    }

    private void recognizeInductionVarsInLoop(Function f, LoopInfo loop) {
        ArrayList<Instruction> instrs = loop.header.getInstructions();
        for (Instruction ins : instrs) {
            if (!(ins instanceof PhiInstr)) {
                break;
            }
            PhiInstr phi = (PhiInstr) ins;
            InductionVarInfo info = matchPhiAsBasicInduction(phi, f, loop);
            if (info != null) {
                inductionVars.put(phi, info);
            }
        }
    }

    /**
     * Match a header phi as a basic IV:
     * - at least one incoming from outside loop, and at least one from inside
     * - all inside incoming edges correspond to the same recurrence i + step (step is constant)
     */
    private InductionVarInfo matchPhiAsBasicInduction(PhiInstr phi, Function f, LoopInfo loop) {
        if (phi.defBlock == null || phi.defBlock.isEmpty()) {
            return null;
        }
        if (phi.getBlock() != loop.header) {
            return null;
        }

        ArrayList<Integer> outsideIdx = new ArrayList<>();
        ArrayList<Integer> insideIdx = new ArrayList<>();
        for (int i = 0; i < phi.defBlock.size(); i++) {
            BasicBlock pred = phi.defBlock.get(i);
            if (pred == null) {
                return null;
            }
            if (loop.blocks.contains(pred)) {
                insideIdx.add(i);
            }
            else {
                outsideIdx.add(i);
            }
        }
        if (outsideIdx.isEmpty() || insideIdx.isEmpty()) {
            return null;
        }

        // Require a single consistent init value for all outside edges.
        Value init = phi.getUseValue(outsideIdx.get(0));
        for (int k = 1; k < outsideIdx.size(); k++) {
            Value other = phi.getUseValue(outsideIdx.get(k));
            if (other == null || !other.equals(init)) {
                return null;
            }
        }

        Integer step = null;
        Value representativeUpdate = null;
        for (int idx : insideIdx) {
            BasicBlock pred = phi.defBlock.get(idx);
            if (!loop.latches.contains(pred)) {
                // Non-latch internal predecessor; conservative: bail for now.
                return null;
            }
            Value update = phi.getUseValue(idx);
            UpdateMatch m = matchUpdateToPhi(update, phi, f, loop);
            if (m == null) {
                return null;
            }
            if (step == null) {
                step = m.step;
                representativeUpdate = m.rootValue;
            }
            else if (!step.equals(m.step)) {
                return null;
            }
        }
        if (step == null) {
            return null;
        }

        return new InductionVarInfo(phi, init, step, representativeUpdate, loop.header, loop.latches, loop.blocks);
    }

    private static class UpdateMatch {
        final int step;
        final Value rootValue;

        UpdateMatch(int step, Value rootValue) {
            this.step = step;
            this.rootValue = rootValue;
        }
    }

    /**
     * Match an update expression to a phi as a recurrence with constant step.
     * Accepts chains of +/- constant where the phi appears exactly once and all intermediate
     * operations are in-loop.
     */
    private UpdateMatch matchUpdateToPhi(Value update, PhiInstr phi, Function f, LoopInfo loop) {
        if (update == null) {
            return null;
        }
        // Fast path: direct alu.
        LinearForm lin = linearizeAddSubChain(update, phi, f, loop);
        if (lin == null || !lin.hasPhi) {
            return null;
        }
        return new UpdateMatch(lin.constSum, update);
    }

    private static class LinearForm {
        final boolean hasPhi;
        final int constSum;

        LinearForm(boolean hasPhi, int constSum) {
            this.hasPhi = hasPhi;
            this.constSum = constSum;
        }
    }

    /**
     * Try to express {@code v} as (phi + const) using only add/sub with ConstantInt.
     * Returns null if the expression uses other varying values or if any intermediate instruction is outside loop.
     */
    private LinearForm linearizeAddSubChain(Value v, PhiInstr phi, Function f, LoopInfo loop) {
        if (v == phi) {
            return new LinearForm(true, 0);
        }
        Integer c = getConstInt(v);
        if (c != null) {
            return new LinearForm(false, c);
        }
        if (!(v instanceof AluInstr)) {
            return null;
        }
        BasicBlock defB = f.findInstrBlock((Instruction) v);
        if (defB == null || !loop.blocks.contains(defB)) {
            return null;
        }

        AluInstr alu = (AluInstr) v;
        String op = alu.getOperator();
        if (!op.equals("+") && !op.equals("-")) {
            return null;
        }

        Value a = alu.getUseValue(0);
        Value b = alu.getUseValue(1);

        LinearForm la = linearizeAddSubChain(a, phi, f, loop);
        LinearForm lb = linearizeAddSubChain(b, phi, f, loop);
        if (la == null || lb == null) {
            return null;
        }
        if (la.hasPhi && lb.hasPhi) {
            // phi appears more than once => not a simple basic recurrence.
            return null;
        }

        if (op.equals("+")) {
            return new LinearForm(la.hasPhi || lb.hasPhi, la.constSum + lb.constSum);
        }
        // op == "-"
        // Disallow const - phi (which would negate phi).
        if (!la.hasPhi && lb.hasPhi) {
            return null;
        }
        return new LinearForm(la.hasPhi || lb.hasPhi, la.constSum - lb.constSum);
    }

    /**
     * PickGep (GEP normalization):
     *
     * Collapse redundant nested GEP chains produced by frontend lowering, especially for arrays:
     *   %p0 = gep base, 0
     *   %p1 = gep %p0, idx
     * ==> %p1 = gep base, idx
     *
     * This is semantics-preserving because the inner GEP contributes a 0 offset.
     * We do it in-place (no new instruction creation) to avoid Builder insertion-point issues.
     */
    public void PickGep() {
        for (Function function : irModule.getFunctions()) {
            boolean changed;
            int iter = 0;
            do {
                changed = false;
                iter++;
                if (iter > 12) {
                    break;
                }

                for (BasicBlock block : function.getBasicBlocks()) {
                    ArrayList<Instruction> instrs = new ArrayList<>(block.getInstructions());
                    for (Instruction ins : instrs) {
                        if (!(ins instanceof GepInstr)) {
                            continue;
                        }
                        GepInstr gep = (GepInstr) ins;
                        Value base = gep.getUseValue(0);
                        if (!(base instanceof GepInstr)) {
                            continue;
                        }
                        GepInstr inner = (GepInstr) base;

                        // Only collapse if inner index is constant 0.
                        Integer innerIdx = getConstInt(inner.getUseValue(1));
                        if (innerIdx == null || innerIdx != 0) {
                            continue;
                        }

                        Value newBase = inner.getUseValue(0);
                        if (newBase == null) {
                            continue;
                        }

                        // Update def-use safely: remove old base-user link, add new one.
                        base.rmUser(gep);
                        gep.setUseValue(0, newBase);
                        newBase.addUser(gep);

                        // If inner GEP becomes dead, remove it.
                        if (inner.getUserList().isEmpty()) {
                            BasicBlock innerBlock = function.findInstrBlock(inner);
                            if (innerBlock != null) {
                                removeInstrAndUnlink(innerBlock, inner);
                            }
                        }

                        changed = true;
                    }
                }
            } while (changed);
        }
    }

    /**
     * RemoveUnusedFunctions: delete functions that are not reachable from main.
     * SysY has a single entry point (main) and no function pointers in this IR, so
     * a simple reachability walk over direct call edges is sufficient.
     *
     * Safety:
     * - Keep main even if not referenced.
     * - Never remove builtin declarations (though they are typically not in the module list).
     */
    public void RemoveUnusedFunctions() {
        ArrayList<Function> all = irModule.getFunctions();
        if (all == null || all.isEmpty()) {
            return;
        }

        Function entry = null;
        for (Function f : all) {
            if (f != null && "main".equals(f.getName())) {
                entry = f;
                break;
            }
        }
        if (entry == null) {
            // No main found; be conservative.
            return;
        }

        HashSet<Function> funcSet = new HashSet<>(all);
        HashSet<Function> reachable = new HashSet<>();
        ArrayDeque<Function> work = new ArrayDeque<>();
        reachable.add(entry);
        work.add(entry);

        while (!work.isEmpty()) {
            Function cur = work.removeFirst();
            for (BasicBlock b : cur.getBasicBlocks()) {
                for (Instruction ins : b.getInstructions()) {
                    if (!(ins instanceof CallInstr)) {
                        continue;
                    }
                    Value calleeV = ins.getUseValue(0);
                    if (!(calleeV instanceof Function)) {
                        continue;
                    }
                    Function callee = (Function) calleeV;
                    if (callee == null) {
                        continue;
                    }
                    if (isBuiltinFunc(callee)) {
                        continue;
                    }
                    // Only track functions that are actually defined in this module.
                    if (!funcSet.contains(callee)) {
                        continue;
                    }
                    if (reachable.add(callee)) {
                        work.add(callee);
                    }
                }
            }
        }

        ArrayList<Function> toRemove = new ArrayList<>();
        for (Function f : new ArrayList<>(all)) {
            if (f == null) {
                continue;
            }
            if (isBuiltinFunc(f)) {
                continue;
            }
            if ("main".equals(f.getName())) {
                continue;
            }
            if (!reachable.contains(f)) {
                toRemove.add(f);
            }
        }
        if (!toRemove.isEmpty()) {
            all.removeAll(toRemove);
        }
    }

    /**
     * After cross-block code motion, ensure each basic block's instruction order respects SSA def-use.
     * This backend assigns stack slots during codegen; if a use appears before its def in the same block,
     * the used value may still have the default memPos=1, producing illegal accesses like -1($sp).
     *
     * Strategy: keep Phi nodes at the top and the terminal at the end;
     * keep pinned instructions in-place as barriers; topologically sort each contiguous non-pinned region.
     */
    public void InstructionResort() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock block : function.getBasicBlocks()) {
                ArrayList<Instruction> instrs = block.getInstructions();
                if (instrs.size() <= 2) {
                    continue;
                }

                Instruction terminal = instrs.get(instrs.size() - 1);
                int end = instrs.size() - 1; // exclusive

                ArrayList<Instruction> newList = new ArrayList<>(instrs.size());
                int idx = 0;
                while (idx < end && instrs.get(idx) instanceof PhiInstr) {
                    newList.add(instrs.get(idx));
                    idx++;
                }

                int segStart = idx;
                while (segStart < end) {
                    int segEnd = segStart;
                    while (segEnd < end && !instrs.get(segEnd).isPinned()) {
                        segEnd++;
                    }

                    if (segEnd > segStart) {
                        newList.addAll(topoSortChunk(instrs.subList(segStart, segEnd)));
                    }
                    if (segEnd < end) {
                        // Pinned instruction as a barrier; keep relative order.
                        newList.add(instrs.get(segEnd));
                        segEnd++;
                    }
                    segStart = segEnd;
                }

                newList.add(terminal);
                instrs.clear();
                instrs.addAll(newList);
            }
        }
    }

    private ArrayList<Instruction> topoSortChunk(List<Instruction> chunk) {
        ArrayList<Instruction> ordered = new ArrayList<>(chunk.size());
        if (chunk.size() <= 1) {
            ordered.addAll(chunk);
            return ordered;
        }

        HashMap<Instruction, Integer> index = new HashMap<>();
        HashMap<Instruction, Integer> indeg = new HashMap<>();
        HashMap<Instruction, ArrayList<Instruction>> adj = new HashMap<>();

        for (int i = 0; i < chunk.size(); i++) {
            Instruction ins = chunk.get(i);
            index.put(ins, i);
            indeg.put(ins, 0);
            adj.put(ins, new ArrayList<>());
        }

        for (Instruction ins : chunk) {
            for (Value op : ins.getOperands()) {
                if (op instanceof Instruction) {
                    Instruction def = (Instruction) op;
                    if (index.containsKey(def)) {
                        adj.get(def).add(ins);
                        indeg.put(ins, indeg.get(ins) + 1);
                    }
                }
            }
        }

        HashSet<Instruction> placed = new HashSet<>();
        boolean progress;
        do {
            progress = false;
            for (Instruction ins : chunk) {
                if (!placed.contains(ins) && indeg.get(ins) == 0) {
                    placed.add(ins);
                    ordered.add(ins);
                    for (Instruction succ : adj.get(ins)) {
                        indeg.put(succ, indeg.get(succ) - 1);
                    }
                    progress = true;
                }
            }
        } while (progress);

        if (ordered.size() != chunk.size()) {
            // Should not happen in well-formed SSA; fall back to original order.
            ordered.clear();
            ordered.addAll(chunk);
        }
        return ordered;
    }

    public void GVN() {
        for (Function f : irModule.getFunctions()) {
            BasicBlock root = f.getBasicBlocks().get(0);
            GVNdfs(root);
            gvn = new HashMap<>();
        }
    }

    public void GVNdfs(BasicBlock node) {
        ArrayList<Instruction> rm = new ArrayList<>();
        ArrayList<String> tomp = new ArrayList<>();
        for (Instruction i : node.getInstructions()) {
            // Only pure calls may participate in value numbering (CSE).
            if (i instanceof CallInstr) {
                Function callee = (Function) i.getUseValue(0);
                if (!isPureFunction(callee)) {
                    continue;
                }
            }
            // Algebraic simplification (safe identities), e.g. x*1=x, x+0=x.
            Value simplified = trySimplifyIdentity(i);
            if (simplified != null) {
                for (Value user : i.getUsers()) {
                    ((User) user).changeUse(i, simplified);
                }
                rm.add(i);
                ArrayList<String> slist = i.tripleString();
                if (!slist.isEmpty()) {
                    tomp.addAll(slist);
                    for (String s : slist) {
                        gvn.put(s, simplified);
                    }
                }
                continue;
            }

            // Constant rewrite (conservative): if instruction can be proven a constant,
            // replace all its uses with that constant and remove it.
            Constant folded = tryFoldConstant(i);
            if (folded != null) {
                Value v = folded;
                for (Value user : i.getUsers()) {
                    ((User) user).changeUse(i, v);
                }
                rm.add(i);
                // Still record this expression's VN -> constant, so later identical expressions fold too.
                ArrayList<String> slist = i.tripleString();
                if (!slist.isEmpty()) {
                    tomp.addAll(slist);
                    for (String s : slist) {
                        gvn.put(s, v);
                    }
                }
                continue;
            }
            ArrayList<String> slist = i.tripleString();
            if (slist.isEmpty()) continue;
            boolean found = false;
            for (String s : slist) {
                if (gvn.containsKey(s)) {
                    found = true;
                    Value v = gvn.get(s);
                    for (Value user : i.getUsers()) {
                        ((User) user).changeUse(i,v);
                    }
                    rm.add(i);
                    break;
                }
            }
            if (!found) {
                tomp.addAll(slist);
                for (String s : slist) {
                    gvn.put(s, i);
                }
            }
        }
        for (Instruction i : rm) {
            node.removeInstr(i);
        }
        for (BasicBlock nxt : node.domTreeNext) {
            GVNdfs(nxt);
        }
        for (String s : tomp) {
            gvn.remove(s);
        }
    }

    private Value trySimplifyIdentity(Instruction instr) {
        if (instr instanceof AluInstr) {
            AluInstr alu = (AluInstr) instr;
            Value a = alu.getUseValue(0);
            Value b = alu.getUseValue(1);
            Integer ca = getConstInt(a);
            Integer cb = getConstInt(b);
            String op = alu.getOperator();

            // Note: only apply identities that are always correct for 32-bit ints and
            // do not remove potential traps (we avoid 0/x and x/0 style transforms).
            if (op.equals("+")) {
                if (ca != null && ca == 0) return b;
                if (cb != null && cb == 0) return a;
            }
            else if (op.equals("-")) {
                if (cb != null && cb == 0) return a;
                if (a.equals(b)) return new ConstantInt(0);
            }
            else if (op.equals("*")) {
                if (ca != null && ca == 0) return new ConstantInt(0);
                if (cb != null && cb == 0) return new ConstantInt(0);
                if (ca != null && ca == 1) return b;
                if (cb != null && cb == 1) return a;
            }
            else if (op.equals("/")) {
                // x/1 = x
                if (cb != null && cb == 1) return a;
            }
            else if (op.equals("%")) {
                // x%1 = 0
                if (cb != null && cb == 1) return new ConstantInt(0);
            }
            return null;
        }

        if (instr instanceof CmpInstr) {
            CmpInstr cmp = (CmpInstr) instr;
            Value a = cmp.getUseValue(0);
            Value b = cmp.getUseValue(1);
            if (!a.equals(b)) {
                return null;
            }
            // For integers: x==x true, x!=x false, x<x false, x<=x true, etc.
            // We don't have direct access to cmp.op (private). Infer via tripleString first token.
            ArrayList<String> ts = cmp.tripleString();
            if (ts.isEmpty()) {
                return null;
            }
            String head = ts.get(0);
            if (head.startsWith("== ")) return new ConstantInt(1);
            if (head.startsWith("!= ")) return new ConstantInt(0);
            if (head.startsWith("< ")) return new ConstantInt(0);
            if (head.startsWith("<= ")) return new ConstantInt(1);
            if (head.startsWith("> ")) return new ConstantInt(0);
            if (head.startsWith(">= ")) return new ConstantInt(1);
            return null;
        }

        return null;
    }

    private Integer getConstInt(Value v) {
        Constant c = v.getValue();
        if (c instanceof ConstantInt) {
            return Integer.parseInt(c.getName());
        }
        return null;
    }

    /**
     * SimplifyIcmp: targeted simplification for CmpInstr (icmp).
     *
     * What it does (safe, local rules):
     * - Constant fold: icmp const,const => 0/1
     * - Same operand: x==x true, x!=x false, x<x false, x<=x true, ...
     * - Redundant boolean compare: for i1 values,
     *      (x != 0) => x,  (x == 1) => x
     * - Signed boundary: x < INT_MIN => false, x >= INT_MIN => true,
     *                   x <= INT_MAX => true, x > INT_MAX => false
     *
     * NOTE: We intentionally avoid creating new instructions here because Builder's insertion
     * point may not be set correctly during optimization.
     */
    public void SimplifyIcmp() {
        for (Function function : irModule.getFunctions()) {
            boolean changed;
            int iter = 0;
            do {
                changed = false;
                iter++;
                if (iter > 8) {
                    break;
                }

                for (BasicBlock block : function.getBasicBlocks()) {
                    ArrayList<Instruction> instrs = new ArrayList<>(block.getInstructions());
                    for (Instruction ins : instrs) {
                        if (!(ins instanceof CmpInstr)) {
                            continue;
                        }

                        CmpInstr cmp = (CmpInstr) ins;
                        String op = getCmpOp(cmp);
                        if (op == null) {
                            continue;
                        }

                        Value a = cmp.getUseValue(0);
                        Value b = cmp.getUseValue(1);

                        // 0) Canonicalize: keep constants on the RHS if possible.
                        Integer ca0 = getConstInt(a);
                        Integer cb0 = getConstInt(b);
                        if (ca0 != null && cb0 == null) {
                            String swapped = swapCmpOp(op);
                            if (swapped != null) {
                                cmp.setUseValue(0, b);
                                cmp.setUseValue(1, a);
                                cmp.setOp(swapped);
                                op = swapped;
                                a = cmp.getUseValue(0);
                                b = cmp.getUseValue(1);
                                changed = true;
                            }
                        }

                        // 1) Constant fold.
                        Constant folded = tryFoldConstant(cmp);
                        if (folded instanceof ConstantInt) {
                            replaceAllUses(cmp, folded);
                            removeInstrAndUnlink(block, cmp);
                            changed = true;
                            continue;
                        }

                        // 2) Same operand.
                        if (a != null && b != null && a.equals(b)) {
                            Value repl = null;
                            if (op.equals("==")) repl = new ConstantInt(1);
                            else if (op.equals("!=")) repl = new ConstantInt(0);
                            else if (op.equals("<")) repl = new ConstantInt(0);
                            else if (op.equals("<=")) repl = new ConstantInt(1);
                            else if (op.equals(">")) repl = new ConstantInt(0);
                            else if (op.equals(">=")) repl = new ConstantInt(1);
                            if (repl != null) {
                                replaceAllUses(cmp, repl);
                                removeInstrAndUnlink(block, cmp);
                                changed = true;
                                continue;
                            }
                        }

                        // 3) BIG WIN: eliminate redundant compare of i1 (or zext(i1)) against 0/1.
                        //    (x != 0) -> x
                        //    (x == 1) -> x
                        // If inversion is needed ((x==0) or (x!=1)) and x is a single-use CmpInstr,
                        // invert x's predicate in-place and drop the outer compare.
                        if (op.equals("==") || op.equals("!=")) {
                            Value x = null;
                            Integer c = null;
                            Integer cb = getConstInt(b);
                            Integer ca = getConstInt(a);
                            if (cb != null) {
                                x = a;
                                c = cb;
                            } else if (ca != null) {
                                x = b;
                                c = ca;
                            }

                            if (x != null && c != null && (c == 0 || c == 1)) {
                                Value i1x = null;
                                if (x.getTypeName().equals("i1")) {
                                    i1x = x;
                                }
                                else {
                                    Value unz = unwrapZextI1(x);
                                    if (unz != null) {
                                        i1x = unz;
                                    }
                                }

                                if (i1x != null) {
                                    if (op.equals("!=") && c == 0) {
                                        replaceAllUses(cmp, i1x);
                                        removeInstrAndUnlink(block, cmp);
                                        changed = true;
                                        continue;
                                    }
                                    if (op.equals("==") && c == 1) {
                                        replaceAllUses(cmp, i1x);
                                        removeInstrAndUnlink(block, cmp);
                                        changed = true;
                                        continue;
                                    }

                                    boolean needInvert = (op.equals("==") && c == 0) || (op.equals("!=") && c == 1);
                                    if (needInvert && i1x instanceof CmpInstr) {
                                        CmpInstr inner = (CmpInstr) i1x;
                                        if (countOperandUsesInFunction(function, inner) == 1) {
                                            String inv = invertCmpOp(getCmpOp(inner));
                                            if (inv != null) {
                                                inner.setOp(inv);
                                                replaceAllUses(cmp, inner);
                                                removeInstrAndUnlink(block, cmp);
                                                changed = true;
                                                continue;
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4) Signed boundary simplifications.
                        Integer cb = getConstInt(b);
                        Integer ca = getConstInt(a);
                        if ((op.equals("<") || op.equals("<=") || op.equals(">") || op.equals(">=")) && cb != null && ca == null) {
                            int c = cb;
                            Value repl = null;
                            if (op.equals("<") && c == Integer.MIN_VALUE) repl = new ConstantInt(0);
                            else if (op.equals(">=") && c == Integer.MIN_VALUE) repl = new ConstantInt(1);
                            else if (op.equals("<=") && c == Integer.MAX_VALUE) repl = new ConstantInt(1);
                            else if (op.equals(">") && c == Integer.MAX_VALUE) repl = new ConstantInt(0);
                            if (repl != null) {
                                replaceAllUses(cmp, repl);
                                removeInstrAndUnlink(block, cmp);
                                changed = true;
                            }
                        }
                    }
                }
            } while (changed);
        }
    }

    private Value unwrapZextI1(Value v) {
        if (!(v instanceof ZextInstr)) {
            return null;
        }
        Value from = ((ZextInstr) v).getUseValue(0);
        if (from != null && from.getTypeName().equals("i1")) {
            return from;
        }
        return null;
    }

    private int countOperandUsesInFunction(Function function, Value v) {
        int cnt = 0;
        for (BasicBlock b : function.getBasicBlocks()) {
            for (Instruction ins : b.getInstructions()) {
                for (Value op : ins.getOperands()) {
                    if (op == v) {
                        cnt++;
                    }
                }
            }
        }
        return cnt;
    }

    private String invertCmpOp(String op) {
        if (op == null) return null;
        if (op.equals("==")) return "!=";
        if (op.equals("!=")) return "==";
        if (op.equals("<")) return ">=";
        if (op.equals("<=")) return ">";
        if (op.equals(">")) return "<=";
        if (op.equals(">=")) return "<";
        return null;
    }

    private String swapCmpOp(String op) {
        if (op == null) return null;
        if (op.equals("==")) return "==";
        if (op.equals("!=")) return "!=";
        if (op.equals("<")) return ">";
        if (op.equals("<=")) return ">=";
        if (op.equals(">")) return "<";
        if (op.equals(">=")) return "<=";
        return null;
    }

    private Constant tryFoldConstant(Instruction instr) {
        // Only fold pure ops that we are confident are side-effect-free and don't depend on memory.
        // This avoids incorrect folding for loads/stores/calls or anything with aliasing.
        if (instr instanceof AluInstr || instr instanceof CmpInstr || instr instanceof ZextInstr) {
            try {
                return instr.getValue();
            } catch (ArithmeticException ex) {
                // e.g. division/mod by zero: do not fold.
                return null;
            }
        }
        if (instr instanceof CallInstr) {
            return tryFoldConstCall((CallInstr) instr, CONST_EVAL_MAX_DEPTH);
        }
        if (instr instanceof PhiInstr) {
            PhiInstr phi = (PhiInstr) instr;
            if (phi.defBlock == null || phi.defBlock.isEmpty()) {
                return null;
            }
            ConstantInt first = null;
            for (int k = 0; k < phi.defBlock.size(); k++) {
                Value v = phi.getUseValue(k);
                if (v == null) {
                    return null;
                }
                Constant c = v.getValue();
                if (!(c instanceof ConstantInt)) {
                    return null;
                }
                ConstantInt ci = (ConstantInt) c;
                if (first == null) {
                    first = ci;
                }
                else if (!first.equals(ci)) {
                    return null;
                }
            }
            if (first == null) {
                return null;
            }
            return new ConstantInt(Integer.parseInt(first.getName()));
        }
        return null;
    }

    private Constant tryFoldConstCall(CallInstr call, int depth) {
        if (depth <= 0) return null;
        Function callee = (Function) call.getUseValue(0);
        if (callee == null || isBuiltinFunc(callee)) {
            return null;
        }
        int argCnt = call.getUseList().size() - 1;
        if (argCnt != callee.getParameters().size()) {
            return null;
        }
        HashMap<Value, Integer> argMap = new HashMap<>();
        for (int i = 0; i < argCnt; i++) {
            Value arg = call.getUseValue(i + 1);
            Constant c = arg.getValue();
            if (!(c instanceof ConstantInt)) {
                return null;
            }
            argMap.put(callee.getParameters().get(i), Integer.parseInt(c.getName()));
        }
        if (!isPureFunction(callee)) {
            return null;
        }
        Integer res = evalConstFunction(callee, argMap, depth - 1);
        if (res == null) return null;
        return new ConstantInt(res);
    }

    private Integer getMappedConst(HashMap<Value, Integer> map, Value v) {
        if (v == null) return null;
        if (map.containsKey(v)) {
            return map.get(v);
        }
        Constant c = v.getValue();
        if (c instanceof ConstantInt) {
            return Integer.parseInt(c.getName());
        }
        return null;
    }

    /**
     * Side-effect analysis (purity): mark functions as pure if they are referentially transparent.
     * Conservative rules (safe for CSE/GVN):
     * - No stores to non-local memory (globals, pointer params, unknown pointers).
     * - No loads from non-local memory (globals, pointer params, unknown pointers).
     * - Calls only to other pure functions; builtin I/O is impure.
     */
    private void analyzeFunctionPurity() {
        pureFunc.clear();
        // Start optimistic for user-defined functions; we will monotonically invalidate.
        for (Function f : irModule.getFunctions()) {
            pureFunc.put(f, !isBuiltinFunc(f));
        }

        boolean changed;
        int guard = 0;
        do {
            guard++;
            changed = false;
            for (Function f : irModule.getFunctions()) {
                if (isBuiltinFunc(f)) {
                    if (pureFunc.getOrDefault(f, true)) {
                        pureFunc.put(f, false);
                        changed = true;
                    }
                    continue;
                }
                boolean was = pureFunc.getOrDefault(f, false);
                if (!was) {
                    continue;
                }

                boolean nowPure = isFunctionPureGivenCurrent(f);
                if (was != nowPure) {
                    pureFunc.put(f, nowPure);
                    changed = true;
                }
            }
        } while (changed && guard < 128);

        purityComputed = true;
    }

    private boolean isPureFunction(Function f) {
        if (f == null) {
            return false;
        }
        if (!purityComputed) {
            analyzeFunctionPurity();
        }
        return pureFunc.getOrDefault(f, false);
    }

    private boolean isFunctionPureGivenCurrent(Function f) {
        // Builtins are impure by definition.
        if (isBuiltinFunc(f)) {
            return false;
        }

        for (BasicBlock b : f.getBasicBlocks()) {
            for (Instruction ins : b.getInstructions()) {
                if (ins instanceof StoreInstr) {
                    Value dst = ins.getUseValue(1);
                    if (accessesNonLocalMemory(dst, f)) {
                        return false;
                    }
                }
                else if (ins instanceof LoadInstr) {
                    Value src = ins.getUseValue(0);
                    if (accessesNonLocalMemory(src, f)) {
                        return false;
                    }
                }
                else if (ins instanceof CallInstr) {
                    Function callee = (Function) ins.getUseValue(0);
                    if (callee == null) {
                        return false;
                    }
                    if (isBuiltinFunc(callee)) {
                        return false;
                    }
                    if (!pureFunc.getOrDefault(callee, false)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean accessesNonLocalMemory(Value addr, Function current) {
        if (addr == null) {
            return true;
        }
        if (addr instanceof GlobalVariable) {
            return true;
        }
        if (addr instanceof AllocaInstr) {
            return false;
        }
        if (addr instanceof GepInstr) {
            Value base = ((GepInstr) addr).getUseValue(0);
            return accessesNonLocalMemory(base, current);
        }

        // Pointer parameters: treat as non-local memory.
        if (current != null) {
            for (Value p : current.getParameters()) {
                if (addr.equals(p)) {
                    return true;
                }
            }
        }

        // Unknown pointer/value => conservatively non-local.
        return true;
    }

    private Integer evalConstFunction(Function f, HashMap<Value, Integer> args, int depth) {
        if (depth < 0) return null;
        if (f.getBasicBlocks().isEmpty()) return null;
        BasicBlock entry = f.getBasicBlocks().get(0);
        BasicBlock cur = entry;
        BasicBlock prev = null;
        HashMap<Value, Integer> valMap = new HashMap<>(args);
        int step = 0;
        while (step < CONST_EVAL_MAX_STEPS) {
            step++;
            ArrayList<Instruction> instrs = cur.getInstructions();
            for (Instruction ins : instrs) {
                if (ins instanceof PhiInstr) {
                    if (prev == null) {
                        return null;
                    }
                    PhiInstr phi = (PhiInstr) ins;
                    int idx = -1;
                    for (int i = 0; i < phi.defBlock.size(); i++) {
                        if (phi.defBlock.get(i).equals(prev)) {
                            idx = i; break;
                        }
                    }
                    if (idx == -1) return null;
                    Integer v = evalConstValue(valMap, phi.getUseValue(idx), depth);
                    if (v == null) return null;
                    valMap.put(phi, v);
                    continue;
                }

                if (ins instanceof BranchInstr) {
                    Integer cond = evalConstValue(valMap, ins.getUseValue(0), depth);
                    if (cond == null) return null;
                    BasicBlock t = ((BranchInstr) ins).getTrueBlock();
                    BasicBlock fl = ((BranchInstr) ins).getFalseBlock();
                    prev = cur;
                    cur = (cond != 0) ? t : fl;
                    break;
                }
                if (ins instanceof JumpInstr) {
                    prev = cur;
                    cur = ((JumpInstr) ins).getBlock();
                    break;
                }
                if (ins instanceof RetInstr) {
                    Integer v = evalConstValue(valMap, ins.getUseValue(0), depth);
                    return v;
                }

                Integer val = null;
                if (ins instanceof AluInstr) {
                    String op = ((AluInstr) ins).getOperator();
                    Integer a = evalConstValue(valMap, ins.getUseValue(0), depth);
                    Integer b = evalConstValue(valMap, ins.getUseValue(1), depth);
                    if (a == null || b == null) return null;
                    try {
                        if (op.equals("+")) val = a + b;
                        else if (op.equals("-")) val = a - b;
                        else if (op.equals("*")) val = a * b;
                        else if (op.equals("/")) val = a / b;
                        else if (op.equals("%")) val = a % b;
                        else return null;
                    } catch (ArithmeticException ex) {
                        return null;
                    }
                }
                else if (ins instanceof CmpInstr) {
                    String op = getCmpOp((CmpInstr) ins);
                    Integer a = evalConstValue(valMap, ins.getUseValue(0), depth);
                    Integer b = evalConstValue(valMap, ins.getUseValue(1), depth);
                    if (op == null || a == null || b == null) return null;
                    boolean res;
                    if (op.equals("==")) res = a.equals(b);
                    else if (op.equals("!=")) res = !a.equals(b);
                    else if (op.equals("<")) res = a < b;
                    else if (op.equals("<=")) res = a <= b;
                    else if (op.equals(">")) res = a > b;
                    else if (op.equals(">=")) res = a >= b;
                    else return null;
                    val = res ? 1 : 0;
                }
                else if (ins instanceof ZextInstr) {
                    Integer a = evalConstValue(valMap, ins.getUseValue(0), depth);
                    if (a == null) return null;
                    val = a;
                }
                else if (ins instanceof CallInstr) {
                    CallInstr ci = (CallInstr) ins;
                    Function cf = (Function) ci.getUseValue(0);
                    if (cf == null || isBuiltinFunc(cf)) return null;
                    int ac = ci.getUseList().size() - 1;
                    if (ac != cf.getParameters().size()) return null;
                    HashMap<Value, Integer> innerArgs = new HashMap<>();
                    for (int i = 0; i < ac; i++) {
                        Integer av = evalConstValue(valMap, ci.getUseValue(i + 1), depth);
                        if (av == null) return null;
                        innerArgs.put(cf.getParameters().get(i), av);
                    }
                    Integer iv = evalConstFunction(cf, innerArgs, depth - 1);
                    if (iv == null) return null;
                    val = iv;
                }
                else if (ins instanceof StoreInstr || ins instanceof LoadInstr || ins instanceof GepInstr
                    || ins instanceof AllocaInstr || ins instanceof PhiInstr) {
                    return null;
                }
                else {
                    return null;
                }

                if (val != null && !ins.getType().toString().equals("void")) {
                    valMap.put(ins, val);
                }
            }
        }
        return null;
    }

    private Integer evalConstValue(HashMap<Value, Integer> valMap, Value v, int depth) {
        if (v == null) return null;
        if (valMap.containsKey(v)) return valMap.get(v);
        Constant c = v.getValue();
        if (c instanceof ConstantInt) {
            return Integer.parseInt(c.getName());
        }
        return null;
    }

    public void rmSimpleEdge() {
        for (Function f : irModule.getFunctions()) {
            BasicBlock nxt = null;
            do {
                for (BasicBlock block : f.getBasicBlocks()) {
                    if (block.next.size() == 1) {
                        for (BasicBlock next : block.next) {
                            nxt = next;
                        }
                        if (nxt.prev.size() == 1) {
                            block.next = nxt.next;
                            for (BasicBlock b : nxt.next) {
                                b.prev.remove(nxt);
                                b.prev.add(block);
                            }
                            instructions = block.getInstructions();
                            block.getInstructions().remove(instructions.size() - 1);
                            for (Instruction i : nxt.getInstructions()) {
                                block.addInstruction(i);
                            }
                            break;
                        } else {
                            nxt = null;
                        }
                    }
                }
                if (nxt != null) f.getBasicBlocks().remove(nxt);
            } while (nxt != null);

        }
    }

    /**
     * TailCall2Loop: transform self tail recursion into a loop inside the same frame.
     * Strategy: materialize parameters into allocas, rewrite tail-recursive call+ret into
     * stores to those allocas followed by a jump to the entry block.
     */
    public void TailCall2Loop() {
        for (Function f : irModule.getFunctions()) {
            if (isBuiltinFunc(f)) {
                continue;
            }
            if (f.getBasicBlocks().isEmpty()) {
                continue;
            }

            ArrayList<BasicBlock> tailBlocks = new ArrayList<>();
            HashMap<BasicBlock, CallInstr> tailCalls = new HashMap<>();
            HashMap<BasicBlock, RetInstr> tailRets = new HashMap<>();

            for (BasicBlock b : f.getBasicBlocks()) {
                ArrayList<Instruction> instrs = b.getInstructions();
                if (instrs.size() < 2) {
                    continue;
                }
                Instruction term = instrs.get(instrs.size() - 1);
                Instruction prev = instrs.get(instrs.size() - 2);
                if (!(term instanceof RetInstr)) {
                    continue;
                }
                if (!(prev instanceof CallInstr)) {
                    continue;
                }
                CallInstr call = (CallInstr) prev;
                if (call.getUseValue(0) != f) {
                    continue; // not self recursion
                }

                RetInstr ret = (RetInstr) term;
                Value retV = ret.getUseValue(0);
                boolean okRet = (retV instanceof llvm.constant.ConstantVoid) || retV == call;
                if (!okRet) {
                    continue;
                }
                if (call.getUsers().size() != 1) {
                    continue; // call value used elsewhere, skip
                }

                tailBlocks.add(b);
                tailCalls.put(b, call);
                tailRets.put(b, ret);
            }

            if (tailBlocks.isEmpty()) {
                continue;
            }

            // Avoid phi-parameter complications.
            boolean hasParamPhiUse = false;
            for (Value param : f.getParameters()) {
                for (Value u : param.getUsers()) {
                    if (u instanceof PhiInstr) {
                        hasParamPhiUse = true;
                        break;
                    }
                }
                if (hasParamPhiUse) break;
            }
            if (hasParamPhiUse) {
                continue;
            }

            // Materialize parameters into allocas in entry block.
            BasicBlock entry = f.getBasicBlocks().get(0);
            ArrayList<Instruction> entryInstrs = entry.getInstructions();
            int insertPos = 0;
            HashMap<Value, AllocaInstr> paramAlloca = new HashMap<>();
            ArrayList<StoreInstr> initStores = new ArrayList<>();
            for (Value param : f.getParameters()) {
                AllocaInstr alloca = new AllocaInstr(param.getType());
                StoreInstr st = new StoreInstr(param, alloca);
                entryInstrs.add(insertPos++, alloca);
                entryInstrs.add(insertPos++, st);
                paramAlloca.put(param, alloca);
                initStores.add(st);
            }

            // Redirect parameter uses to loads from allocas.
            for (Value param : f.getParameters()) {
                AllocaInstr alloca = paramAlloca.get(param);
                ArrayList<Value> users = new ArrayList<>(param.getUsers());
                for (Value u : users) {
                    if (!(u instanceof Instruction)) {
                        continue;
                    }
                    Instruction ins = (Instruction) u;
                    if (initStores.contains(ins)) {
                        continue; // keep the initial store's use of param
                    }
                    BasicBlock ub = f.findInstrBlock(ins);
                    if (ub == null) {
                        continue;
                    }
                    ArrayList<Instruction> list = ub.getInstructions();
                    int idx = list.indexOf(ins);
                    if (idx < 0) {
                        continue;
                    }
                    LoadInstr ld = new LoadInstr(alloca);
                    list.add(idx, ld);
                    ((User) ins).changeUse(param, ld);
                    param.rmUser((User) ins);
                }
            }

            // Rewrite tail-recursive blocks to loop to entry.
            for (BasicBlock b : tailBlocks) {
                CallInstr call = tailCalls.get(b);
                RetInstr ret = tailRets.get(b);
                ArrayList<Instruction> instrs = b.getInstructions();

                removeInstrAndUnlink(b, call);
                removeInstrAndUnlink(b, ret);

                int argCnt = call.getUseList().size() - 1;
                for (int i = 0; i < argCnt; i++) {
                    Value arg = call.getUseValue(i + 1);
                    Value param = f.getParameters().get(i);
                    AllocaInstr alloca = paramAlloca.get(param);
                    instrs.add(new StoreInstr(arg, alloca));
                }

                instrs.add(new JumpInstr(entry));

                b.next.clear();
                b.addNextBlock(entry);
                entry.addPrevBlock(b);
            }

            // Refresh CFG edges after transformations.
            rebuildCFGEdges(f);
        }
    }

    /**
     * FuncInline: inline small, single-block functions directly at call sites to avoid call overhead.
     * Conservative policy: only inline non-builtin, non-recursive, single-basic-block callees
     * containing only simple, cloneable instructions and ending with a Ret.
     */
    public void FuncInline() {
        boolean changed;
        do {
            changed = false;
            for (Function caller : irModule.getFunctions()) {
                for (BasicBlock block : caller.getBasicBlocks()) {
                    ArrayList<Instruction> instrs = new ArrayList<>(block.getInstructions());
                    for (int idx = 0; idx < instrs.size(); idx++) {
                        Instruction ins = instrs.get(idx);
                        if (!(ins instanceof CallInstr)) {
                            continue;
                        }
                        CallInstr call = (CallInstr) ins;
                        Function callee = (Function) call.getUseValue(0);
                        if (!canInlineFunction(caller, callee, call)) {
                            continue;
                        }
                        if (inlineCall(caller, block, idx, call, callee)) {
                            changed = true;
                            break;
                        }
                    }
                    if (changed) break;
                }
                if (changed) break;
            }
        } while (changed);
    }

    private boolean canInlineFunction(Function caller, Function callee, CallInstr call) {
        if (callee == null || caller == null) return false;
        if (isBuiltinFunc(callee)) return false;
        if (callee == caller) return false; // avoid self-recursion (handled by tail call pass)
        ArrayList<BasicBlock> cBlocks = callee.getBasicBlocks();
        if (cBlocks.size() != 1) return false;
        ArrayList<Instruction> cInstrs = cBlocks.get(0).getInstructions();
        if (cInstrs.isEmpty()) return false;
        Instruction term = cInstrs.get(cInstrs.size() - 1);
        if (!(term instanceof RetInstr)) return false;
        if (call.getUseList().size() - 1 != callee.getParameters().size()) return false;

        // Only allow a small, straight-line body with cloneable instructions and no allocas.
        for (int i = 0; i < cInstrs.size() - 1; i++) {
            Instruction ci = cInstrs.get(i);
            if (!isInlineCloneable(ci)) return false;
        }

        return true;
    }

    private boolean inlineCall(Function caller, BasicBlock block, int callIdx, CallInstr call, Function callee) {
        BasicBlock cBlock = callee.getBasicBlocks().get(0);
        ArrayList<Instruction> body = cBlock.getInstructions();
        RetInstr ret = (RetInstr) body.get(body.size() - 1);

        HashMap<Value, Value> vmap = new HashMap<>();
        for (int i = 0; i < callee.getParameters().size(); i++) {
            Value param = callee.getParameters().get(i);
            Value arg = call.getUseValue(i + 1);
            vmap.put(param, arg);
        }

        int insertPos = callIdx;
        for (int i = 0; i < body.size() - 1; i++) {
            Instruction orig = body.get(i);
            Instruction clone = cloneInlineInstr(orig, vmap);
            if (clone == null) {
                return false;
            }
            block.getInstructions().add(insertPos, clone);
            insertPos++;
            if (!clone.getType().toString().equals("void")) {
                vmap.put(orig, clone);
            }
        }

        Value retVal = ret.getUseValue(0);
        Value mappedRet = mapInlineValue(retVal, vmap);
        if (!(retVal instanceof llvm.constant.ConstantVoid)) {
            ArrayList<Value> users = new ArrayList<>(call.getUsers());
            for (Value u : users) {
                if (u instanceof User) {
                    ((User) u).changeUse(call, mappedRet);
                }
            }
        }

        removeInstrAndUnlink(block, call);
        return true;
    }

    private Instruction cloneInlineInstr(Instruction orig, HashMap<Value, Value> vmap) {
        if (orig instanceof AllocaInstr) {
            return null; // avoid introducing mid-block allocas
        }
        if (orig instanceof LoadInstr) {
            Value from = mapInlineValue(orig.getUseValue(0), vmap);
            return new LoadInstr(from);
        }
        if (orig instanceof StoreInstr) {
            Value in = mapInlineValue(orig.getUseValue(0), vmap);
            Value to = mapInlineValue(orig.getUseValue(1), vmap);
            return new StoreInstr(in, to);
        }
        if (orig instanceof AluInstr) {
            String op = ((AluInstr) orig).getOperator();
            Value a = mapInlineValue(orig.getUseValue(0), vmap);
            Value b = mapInlineValue(orig.getUseValue(1), vmap);
            return new AluInstr(a, op, b);
        }
        if (orig instanceof CmpInstr) {
            String op = getCmpOp((CmpInstr) orig);
            if (op == null) return null;
            Value a = mapInlineValue(orig.getUseValue(0), vmap);
            Value b = mapInlineValue(orig.getUseValue(1), vmap);
            return new CmpInstr(a, op, b);
        }
        if (orig instanceof ZextInstr) {
            Value a = mapInlineValue(orig.getUseValue(0), vmap);
            return new ZextInstr(orig.getType(), a);
        }
        if (orig instanceof GepInstr) {
            Value base = mapInlineValue(orig.getUseValue(0), vmap);
            Value idx = mapInlineValue(orig.getUseValue(1), vmap);
            return new GepInstr(base, idx);
        }
        return null;
    }

    private Value mapInlineValue(Value v, HashMap<Value, Value> vmap) {
        if (vmap.containsKey(v)) {
            return vmap.get(v);
        }
        return v;
    }

    private boolean isInlineCloneable(Instruction ins) {
        return (ins instanceof LoadInstr) || (ins instanceof StoreInstr) || (ins instanceof AluInstr)
            || (ins instanceof CmpInstr) || (ins instanceof ZextInstr) || (ins instanceof GepInstr);
    }

    private String getCmpOp(CmpInstr cmp) {
        // Prefer direct field access if available (more reliable than parsing tripleString).
        try {
            String op = cmp.getOp();
            if (op != null) return op;
        } catch (Throwable ignored) {
        }
        ArrayList<String> ts = cmp.tripleString();
        if (ts.isEmpty()) return null;
        String head = ts.get(0);
        int sp = head.indexOf(' ');
        if (sp <= 0) return null;
        return head.substring(0, sp);
    }

    public void liveAnalyse() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock block : function.getBasicBlocks()) {
                block.initUseDef();
            }
        }
        for (Function function : irModule.getFunctions()) {
            boolean flag = true;
            while (flag) {
                flag = false;
                for (BasicBlock block : function.getBasicBlocks()) {
                    boolean change = block.updateLive();
                    if (change) {
                        flag = true;
                    }
                }
            }
        }
    }

    /**
     * RemoveJumpOnlyBlocks: collapse basic blocks that only contain an unconditional jump.
     * Rewires predecessors directly to the successor and updates Phi nodes accordingly.
     */
    public void RemoveJumpOnlyBlocks() {
        for (Function f : irModule.getFunctions()) {
            boolean changed;
            do {
                changed = false;
                ArrayList<BasicBlock> blocks = new ArrayList<>(f.getBasicBlocks());
                for (BasicBlock b : blocks) {
                    ArrayList<Instruction> instrs = b.getInstructions();
                    if (instrs.size() != 1) continue;
                    Instruction term = instrs.get(0);
                    if (!(term instanceof JumpInstr)) continue;
                    if (f.getBasicBlocks().isEmpty() || b.equals(f.getBasicBlocks().get(0))) continue;

                    BasicBlock succ = ((JumpInstr) term).getBlock();
                    if (succ == b) continue;

                    ArrayList<BasicBlock> preds = new ArrayList<>(b.prev);
                    if (preds.isEmpty()) continue;

                    // SSA safety: removing b can create multiple incoming edges from the same predecessor
                    // into succ's Phi nodes (e.g., if-without-else after Mem2Reg).
                    // This project represents phi operands keyed by predecessor block, so duplicates are invalid
                    // and can silently turn a conditional assignment into an unconditional one.
                    boolean safeToRemove = true;
                    ArrayList<Instruction> succInstrs0 = succ.getInstructions();
                    for (Instruction ins : succInstrs0) {
                        if (!(ins instanceof PhiInstr)) break;
                        PhiInstr phi = (PhiInstr) ins;
                        java.util.HashSet<BasicBlock> existing = new java.util.HashSet<>(phi.defBlock);
                        existing.remove(b);

                        // If any predecessor would collide with an existing incoming, bail out.
                        for (BasicBlock pred : preds) {
                            if (existing.contains(pred)) {
                                safeToRemove = false;
                                break;
                            }
                        }
                        if (!safeToRemove) {
                            break;
                        }

                        // If phi already has multiple entries from b, removal would replicate preds multiple times.
                        int cntFromB = 0;
                        for (BasicBlock bb : phi.defBlock) {
                            if (bb.equals(b)) cntFromB++;
                        }
                        if (cntFromB > 1) {
                            safeToRemove = false;
                            break;
                        }
                    }
                    if (!safeToRemove) {
                        continue;
                    }

                    // Update Phi nodes in successor: replace incoming from b with each predecessor of b.
                    ArrayList<Instruction> succInstrs = succ.getInstructions();
                    for (Instruction ins : succInstrs) {
                        if (!(ins instanceof PhiInstr)) break;
                        PhiInstr phi = (PhiInstr) ins;
                        ArrayList<Integer> idxs = new ArrayList<>();
                        for (int i = phi.defBlock.size() - 1; i >= 0; i--) {
                            if (phi.defBlock.get(i).equals(b)) {
                                idxs.add(i);
                            }
                        }
                        for (Integer idx : idxs) {
                            Value val = phi.getUseValue(idx);
                            if (val != null) {
                                val.rmUser(phi);
                            }
                            phi.defBlock.remove((int) idx);
                            phi.getUseList().remove((int) idx);
                            phi.values.remove((int) idx);

                            for (BasicBlock pred : preds) {
                                phi.defBlock.add(pred);
                                phi.addUseValue(val);
                            }
                        }
                    }

                    // Rewire predecessors' terminators and CFG edges.
                    for (BasicBlock pred : preds) {
                        ArrayList<Instruction> pinstrs = pred.getInstructions();
                        if (pinstrs.isEmpty()) continue;
                        Instruction pterm = pinstrs.get(pinstrs.size() - 1);
                        if (pterm instanceof BranchInstr) {
                            ((BranchInstr) pterm).replaceBlock(b, succ);
                        } else if (pterm instanceof JumpInstr) {
                            ((JumpInstr) pterm).setUseValue(0, succ);
                        }
                        pred.removeNextBlock(b);
                        pred.addNextBlock(succ);
                    }

                    succ.removePrevBlock(b);
                    for (BasicBlock pred : preds) {
                        succ.addPrevBlock(pred);
                    }

                    // Remove the jump-only block and unlink its instruction.
                    removeInstrAndUnlink(b, term);
                    f.removeBlock(b);

                    changed = true;
                    break;
                }
            } while (changed);

            // Rebuild CFG edges to ensure consistency.
            rebuildCFGEdges(f);
        }
    }

    /**
     * DeadStoreEliminate: remove stores to non-escaping local allocas when no subsequent load can reach them.
     * Alias model is intentionally simple: track per-alloca, ignore cross-alloca aliasing, and only touch
     * allocas whose address never escapes (only used by load/store/gep).
     */
    public void DeadStoreEliminate() {
        for (Function function : irModule.getFunctions()) {
            // Collect candidate allocas whose address does not escape the function.
            HashSet<AllocaInstr> candidates = new HashSet<>();
            for (BasicBlock block : function.getBasicBlocks()) {
                for (Instruction ins : block.getInstructions()) {
                    if (ins instanceof AllocaInstr) {
                        AllocaInstr alloca = (AllocaInstr) ins;
                        if (!isAllocaEscaping(alloca)) {
                            candidates.add(alloca);
                        }
                    }
                }
            }
            if (candidates.isEmpty()) {
                continue;
            }

            HashMap<BasicBlock, HashSet<AllocaInstr>> inAlive = new HashMap<>();
            HashMap<BasicBlock, HashSet<AllocaInstr>> outAlive = new HashMap<>();
            for (BasicBlock b : function.getBasicBlocks()) {
                inAlive.put(b, new HashSet<>());
                outAlive.put(b, new HashSet<>());
            }

            boolean changed = true;
            while (changed) {
                changed = false;
                for (BasicBlock b : function.getBasicBlocks()) {
                    HashSet<AllocaInstr> newOut = new HashSet<>();
                    for (BasicBlock succ : b.next) {
                        HashSet<AllocaInstr> succIn = inAlive.get(succ);
                        if (succIn != null) {
                            newOut.addAll(succIn);
                        }
                    }
                    if (!newOut.equals(outAlive.get(b))) {
                        outAlive.put(b, newOut);
                        changed = true;
                    }

                    HashSet<AllocaInstr> newIn = computeAliveIn(b, newOut, candidates);
                    if (!newIn.equals(inAlive.get(b))) {
                        inAlive.put(b, newIn);
                        changed = true;
                    }
                }
            }

            for (BasicBlock b : function.getBasicBlocks()) {
                HashSet<AllocaInstr> alive = new HashSet<>(outAlive.get(b));
                ArrayList<Instruction> toRemove = new ArrayList<>();
                ArrayList<Instruction> instrs = b.getInstructions();
                for (int i = instrs.size() - 1; i >= 0; i--) {
                    Instruction ins = instrs.get(i);
                    if (ins instanceof LoadInstr) {
                        AllocaInstr base = getAllocaBase(((LoadInstr) ins).getUseValue(0), candidates);
                        if (base != null) {
                            alive.add(base);
                        }
                    }
                    else if (ins instanceof StoreInstr) {
                        AllocaInstr base = getAllocaBase(((StoreInstr) ins).getUseValue(1), candidates);
                        if (base != null) {
                            if (!alive.contains(base)) {
                                toRemove.add(ins);
                            }
                            alive.remove(base);
                        }
                    }
                }
                for (Instruction rm : toRemove) {
                    removeInstrAndUnlink(b, rm);
                }
            }
        }
    }

    private HashSet<AllocaInstr> computeAliveIn(BasicBlock block, HashSet<AllocaInstr> out,
                                                HashSet<AllocaInstr> candidates) {
        HashSet<AllocaInstr> alive = new HashSet<>(out);
        ArrayList<Instruction> instrs = block.getInstructions();
        for (int i = instrs.size() - 1; i >= 0; i--) {
            Instruction ins = instrs.get(i);
            if (ins instanceof LoadInstr) {
                AllocaInstr base = getAllocaBase(((LoadInstr) ins).getUseValue(0), candidates);
                if (base != null) {
                    alive.add(base);
                }
            }
            else if (ins instanceof StoreInstr) {
                AllocaInstr base = getAllocaBase(((StoreInstr) ins).getUseValue(1), candidates);
                if (base != null) {
                    alive.remove(base);
                }
            }
        }
        return alive;
    }

    private AllocaInstr getAllocaBase(Value ptr, HashSet<AllocaInstr> candidates) {
        Value cur = ptr;
        while (cur instanceof GepInstr) {
            cur = ((GepInstr) cur).getUseValue(0);
        }
        if (cur instanceof AllocaInstr && candidates.contains(cur)) {
            return (AllocaInstr) cur;
        }
        return null;
    }

    private boolean isAllocaEscaping(AllocaInstr alloca) {
        ArrayList<Value> worklist = new ArrayList<>();
        HashSet<Value> vis = new HashSet<>();
        worklist.add(alloca);
        vis.add(alloca);

        while (!worklist.isEmpty()) {
            Value cur = worklist.remove(worklist.size() - 1);
            for (Value user : cur.getUsers()) {
                if (user instanceof GepInstr) {
                    GepInstr g = (GepInstr) user;
                    if (g.getUseValue(0) != cur) {
                        return true;
                    }
                    if (!vis.contains(user)) {
                        vis.add(user);
                        worklist.add(user);
                    }
                }
                else if (user instanceof LoadInstr) {
                    if (((LoadInstr) user).getUseValue(0) != cur) {
                        return true;
                    }
                }
                else if (user instanceof StoreInstr) {
                    StoreInstr st = (StoreInstr) user;
                    if (st.getUseValue(1) != cur) {
                        return true;
                    }
                }
                else {
                    return true;
                }
            }
        }
        return false;
    }

    public void GCM() {
        for (Function function : irModule.getFunctions()) {
            BasicBlock root = function.getBasicBlocks().get(0);
            HashSet<Instruction> vis = new HashSet<>();
            for (BasicBlock block : function.getBasicBlocks()) {
                for (Instruction instruction : block.getInstructions()) {
                    scheduleEarly(instruction, vis, function);
                }
            }

            vis = new HashSet<>();
            for (BasicBlock block : function.getBasicBlocks()) {
                for (Instruction instruction : block.getInstructions()) {
                    scheduleLate(instruction, vis, function);
                }
            }

            // Collect movable instructions first to avoid mutating instruction lists while iterating them.
            ArrayList<Instruction> movable = new ArrayList<>();
            for (BasicBlock block : function.getBasicBlocks()) {
                for (Instruction instruction : block.getInstructions()) {
                    if (!(instruction instanceof PhiInstr) && !instruction.isTerminal() && !instruction.isPinned()) {
                        movable.add(instruction);
                    }
                }
            }

            for (Instruction instruction : movable) {
                if (instruction.isFloated) {
                    continue;
                }
                BasicBlock best = instruction.lateBlock;
                BasicBlock curr = instruction.lateBlock;

                if (instruction.earlyBlock == null) {
                    best = root;
                    curr = root;
                    instruction.earlyBlock = root;
                }

                // Choose best block on the dom-chain between [late, early].
                while (curr != instruction.earlyBlock) {
                    if (curr == null) {
                        // Defensive: if the chain is broken, fall back to earlyBlock.
                        best = instruction.earlyBlock;
                        break;
                    }
                    if (best != null && curr.cycleDepth < best.cycleDepth) {
                        best = curr;
                    }
                    curr = curr.directDom;
                }
                if (best == null || instruction.earlyBlock.cycleDepth < best.cycleDepth) {
                    best = instruction.earlyBlock;
                }

                // Safety: the chosen block must dominate every use. Otherwise, the backend may read
                // an uninitialized stack slot (causing wrong control flow / wrong answers).
                if (best != null && !dominatesAllUses(function, best, instruction)) {
                    best = instruction.lateBlock;
                }
                if (best != null && !dominatesAllUses(function, best, instruction)) {
                    // Give up moving this instruction if we cannot find a dominating placement.
                    instruction.isFloated = true;
                    continue;
                }
                instruction.targetBlock = best;

                BasicBlock from = function.findInstrBlock(instruction);
                if (from == null || best == null || best == from) {
                    // Don't reshuffle within the same block: it can easily create use-before-def in this IR.
                    instruction.isFloated = true;
                    continue;
                }

                int insertIndex = findSafeInsertIndex(function, best, instruction);
                if (insertIndex < 0) {
                    // If we cannot find a safe position in the target block, keep it in place.
                    instruction.isFloated = true;
                    continue;
                }

                from.removeInstr(instruction);
                best.getInstructions().add(insertIndex, instruction);
                instruction.isFloated = true;
            }
        }
    }

    /**
     * Find a safe insertion index for moving {@code instr} into {@code target}.
     * Requirements (in the target block):
     * - After all Phi instructions.
     * - After any operand definitions that are also in {@code target}.
     * - Before the first non-Phi use of {@code instr} that is also in {@code target}.
     * - Before the terminal instruction.
     * Returns -1 if no safe insertion position exists.
     */
    private int findSafeInsertIndex(Function function, BasicBlock target, Instruction instr) {
        ArrayList<Instruction> list = target.getInstructions();
        if (list.isEmpty()) {
            return -1;
        }
        int beforeTerminal = list.size() - 1;
        if (beforeTerminal < 0) {
            return -1;
        }

        int afterPhi = 0;
        while (afterPhi < list.size() && list.get(afterPhi) instanceof PhiInstr) {
            afterPhi++;
        }

        int afterOperands = afterPhi;
        for (Value op : instr.getOperands()) {
            if (op instanceof Instruction) {
                Instruction def = (Instruction) op;
                BasicBlock defBlock = function.findInstrBlock(def);
                if (defBlock == target) {
                    int defIndex = list.indexOf(def);
                    if (defIndex >= 0) {
                        afterOperands = Math.max(afterOperands, defIndex + 1);
                    }
                }
            }
        }

        int firstUse = beforeTerminal;
        boolean hasLocalUse = false;
        for (Value userV : instr.getUsers()) {
            if (userV instanceof Instruction) {
                Instruction user = (Instruction) userV;
                if (user instanceof PhiInstr) {
                    continue;
                }
                BasicBlock useBlock = function.findInstrBlock(user);
                if (useBlock == target) {
                    int useIndex = list.indexOf(user);
                    if (useIndex >= 0) {
                        hasLocalUse = true;
                        firstUse = Math.min(firstUse, useIndex);
                    }
                }
            }
        }

        int insertIndex;
        if (hasLocalUse) {
            // Must be placed strictly before the first local use.
            insertIndex = afterOperands;
            if (insertIndex > firstUse) {
                return -1;
            }
        } else {
            // No local uses: place as late as possible while keeping operand order.
            insertIndex = Math.min(beforeTerminal, Math.max(afterOperands, beforeTerminal));
        }

        if (insertIndex > beforeTerminal) {
            return -1;
        }
        return insertIndex;
    }

    private boolean dominatesAllUses(Function function, BasicBlock candidate, Instruction instr) {
        if (candidate == null) {
            return false;
        }
        for (Value userV : instr.getUsers()) {
            if (!(userV instanceof Instruction)) {
                continue;
            }
            Instruction user = (Instruction) userV;
            BasicBlock useBlock = function.findInstrBlock(user);
            if (user instanceof PhiInstr) {
                useBlock = ((PhiInstr) user).findPrevBlock(instr);
            }
            if (useBlock == null) {
                continue;
            }
            // BasicBlock.dom stores dominators of this block.
            if (!useBlock.isDominate(candidate)) {
                return false;
            }
        }
        return true;
    }

    public void scheduleLate(Instruction instr, HashSet<Instruction> vis, Function function) {
        if (vis.contains(instr)) {
            return;
        }
        vis.add(instr);
        if (instr.isPinned()) {
            instr.lateBlock = function.findInstrBlock(instr);
            return;
        }
        BasicBlock late = instr.lateBlock;
        for (Value value : instr.getUsers()) {
            if (value instanceof Instruction) {
                Instruction instr2 = (Instruction) value;
                scheduleLate(instr2, vis, function);
                BasicBlock use = instr2.lateBlock;
                if (instr2 instanceof PhiInstr) {
                    use = ((PhiInstr) instr2).findPrevBlock(instr);
                }
                if (use == null) {
                    continue;
                }
                if (late == null) {
                    late = use;
                }
                else {
                    late = findLCA(late,use);
                }
            }
        }
        instr.lateBlock = late;
    }

    public BasicBlock findLCA(BasicBlock A, BasicBlock B) {
        BasicBlock tmpA = A;
        BasicBlock tmpB = B;
        while (tmpA.domDepth > tmpB.domDepth) {
            tmpA = tmpA.directDom;
        }
        while (tmpB.domDepth > tmpA.domDepth) {
            tmpB = tmpB.directDom;
        }
        while (tmpA != tmpB) {
            tmpA = tmpA.directDom;
            tmpB = tmpB.directDom;
        }
        return tmpA;
    }

    public void scheduleEarly(Instruction instr, HashSet<Instruction> vis, Function function) {
        if (vis.contains(instr)) {
            return;
        }
        vis.add(instr);
        BasicBlock root = function.getBasicBlocks().get(0);
        if (instr.isPinned()) {
            instr.earlyBlock = function.findInstrBlock(instr);
        }
        else {
            instr.earlyBlock = root;
        }
        for (Value value : instr.getOperands()) {
            if (value instanceof Instruction) {
                Instruction x = (Instruction) value;
                scheduleEarly(x, vis, function);
                if (instr.earlyBlock.domDepth < x.earlyBlock.domDepth) {
                    instr.earlyBlock = x.earlyBlock;
                }
            }
        }
    }

    public void initDomTree() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                if (basicBlock.directDom != null) {
                    basicBlock.directDom.domTreeNext.add(basicBlock);
                }
            }
            function.getBasicBlocks().get(0).updateDomDepth(0);
        }
    }

    public void rename() {
        for (Function function : irModule.getFunctions()) {
            int cnt = 0;
            for (BasicBlock block : function.getBasicBlocks()) {
                block.setName("b"+cnt);
                cnt++;
                for (Instruction instruction : block.getInstructions()) {
                    instruction.setName("%v"+cnt);
                    cnt++;
                }
            }
        }
    }

    /**
     * CopyPropagation: eliminate redundant SSA copies by forwarding operands to their canonical defs.
     * Handles: zext (as a value-preserving copy) and degenerate phi nodes with identical incoming values.
     * Strategy: forward dataflow over CFG with intersection meet; rewrite operands using the current alias map.
     */
    public void CopyPropagation() {
        for (Function function : irModule.getFunctions()) {
            ArrayList<BasicBlock> blocks = function.getBasicBlocks();
            if (blocks.isEmpty()) {
                continue;
            }

            HashMap<BasicBlock, HashMap<Value, Value>> outMap = new HashMap<>();
            ArrayDeque<BasicBlock> work = new ArrayDeque<>();
            work.add(blocks.get(0));

            while (!work.isEmpty()) {
                BasicBlock block = work.poll();
                HashMap<Value, Value> inEnv = meetCopies(block, outMap);
                HashMap<Value, Value> newOut = transferCopies(block, inEnv);

                HashMap<Value, Value> oldOut = outMap.get(block);
                if (!mapsEqual(oldOut, newOut)) {
                    outMap.put(block, newOut);
                    for (BasicBlock succ : block.next) {
                        work.add(succ);
                    }
                }
            }
        }
    }

    private HashMap<Value, Value> meetCopies(BasicBlock block, HashMap<BasicBlock, HashMap<Value, Value>> outMap) {
        if (block.prev.isEmpty()) {
            return new HashMap<>();
        }

        HashMap<Value, Value> res = null;
        for (BasicBlock pred : block.prev) {
            HashMap<Value, Value> env = outMap.get(pred);
            if (env == null) {
                return new HashMap<>();
            }

            HashMap<Value, Value> canonEnv = new HashMap<>();
            for (Map.Entry<Value, Value> e : env.entrySet()) {
                canonEnv.put(e.getKey(), findCanonical(env, e.getValue()));
            }

            if (res == null) {
                res = new HashMap<>(canonEnv);
            }
            else {
                res.entrySet().removeIf(entry -> {
                    Value other = canonEnv.get(entry.getKey());
                    return other == null || !other.equals(entry.getValue());
                });
            }
        }

        return res == null ? new HashMap<>() : res;
    }

    private HashMap<Value, Value> transferCopies(BasicBlock block, HashMap<Value, Value> inEnv) {
        HashMap<Value, Value> env = new HashMap<>(inEnv);

        for (Instruction instr : block.getInstructions()) {
            rewriteOperands(instr, env);

            Value copySrc = getCopySource(instr);
            if (copySrc != null) {
                Value canon = findCanonical(env, copySrc);
                env.put(instr, canon);
            }
        }

        return env;
    }

    private void rewriteOperands(Instruction instr, HashMap<Value, Value> env) {
        ArrayList<Value> ops = instr.getOperands();
        for (int i = 0; i < ops.size(); i++) {
            Value old = ops.get(i);
            Value rep = findCanonical(env, old);
            if (rep != null && rep != old) {
                old.rmUser(instr);
                instr.setUseValue(i, rep);
                rep.addUser(instr);
            }
        }
    }

    private Value getCopySource(Instruction instr) {
        if (instr instanceof llvm.instr.ZextInstr) {
            return instr.getUseValue(0);
        }
        if (instr instanceof PhiInstr) {
            if (((PhiInstr) instr).defBlock.isEmpty()) {
                return null;
            }
            Value same = instr.getUseValue(0);
            for (int i = 1; i < ((PhiInstr) instr).defBlock.size(); i++) {
                Value v = instr.getUseValue(i);
                if (v == null || !v.equals(same)) {
                    return null;
                }
            }
            return same;
        }
        return null;
    }

    private Value findCanonical(HashMap<Value, Value> env, Value v) {
        Value cur = v;
        HashSet<Value> seen = new HashSet<>();
        while (env.containsKey(cur) && !seen.contains(cur)) {
            seen.add(cur);
            Value next = env.get(cur);
            if (next == null) {
                break;
            }
            cur = next;
        }
        return cur;
    }

    private boolean mapsEqual(HashMap<Value, Value> a, HashMap<Value, Value> b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<Value, Value> e : a.entrySet()) {
            Value v = b.get(e.getKey());
            if (v == null || !v.equals(e.getValue())) {
                return false;
            }
        }
        return true;
    }

    public void rmPhi() {
        functions = irModule.getFunctions();
        for (Function f : functions) {
            basicBlocks = f.getBasicBlocks();
            for (BasicBlock b : basicBlocks) {
                ArrayList<PhiInstr> phiInstrs = new ArrayList<>();
                for (Instruction i : b.getInstructions()) {
                    if (i instanceof PhiInstr) {
                        phiInstrs.add((PhiInstr) i);
                    }
                    else break;
                }
                for (PhiInstr p : phiInstrs) {
                    p.addMove();
                }
            }
        }
    }

    public void rmKeyEdge() {
        functions = irModule.getFunctions();
        for (Function f : functions) {
            basicBlocks = new ArrayList<>(f.getBasicBlocks());
            for (BasicBlock A : basicBlocks) {
                ArrayList<BasicBlock> Anext = new ArrayList<>(A.next);
                for (BasicBlock B : Anext) {
                    if(A.next.size() > 1 && B.prev.size() > 1) {
                        BasicBlock T = new BasicBlock();
                        A.replaceNextBlock(B,T);
                        B.replacePrevBlock(A,T);
                        f.addBasicBlock(T);
                        T.setFatherFunction(f);
                        T.addInstruction(new JumpInstr(B));

                        A.addNextBlock(T);
                        T.addPrevBlock(A);
                        T.addNextBlock(B);
                        B.addPrevBlock(T);
                        A.removeNextBlock(B);
                        B.removePrevBlock(A);
                    }
                }
            }
        }
    }

    public void renameSSA() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                Iterator<Instruction> iterator = basicBlock.getInstructions().iterator();
                while (iterator.hasNext()) {
                    Instruction instruction = iterator.next();
                    if (instruction instanceof StoreInstr && ((StoreInstr) instruction).toVar()) {
                        ((StoreInstr) instruction).store(basicBlock);
                        iterator.remove();
                    }
                    else if (instruction instanceof LoadInstr && ((LoadInstr) instruction).fromVar()) {
                        ((LoadInstr) instruction).load(basicBlock);
                        iterator.remove();
                    }
                    else if (instruction instanceof AllocaInstr && instruction.getType().ptTo().toString().equals("i32")) {
                        iterator.remove();
                    }
                    else if (instruction instanceof PhiInstr) {
                        ((PhiInstr) instruction).initPhi(basicBlock);
                    }
                }
            }
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                for (Instruction instruction : basicBlock.getInstructions()) {
                    if (instruction instanceof PhiInstr) {
                        ((PhiInstr) instruction).updatePhi(basicBlock);
                    }
                }
            }
            int cnt = 0;
            for (BasicBlock block : function.getBasicBlocks()) {
                block.setName("b"+cnt);
                cnt++;
                for (Instruction instruction : block.getInstructions()) {
                    instruction.setName("%v"+cnt);
                    cnt++;
                }
            }
        }
    }

    /**
     * LocalArrayLift: promote const local arrays to globals to shrink stack usage and reuse global data.
     * Safety: only lift arrays that are const (declared const) and never written via store.
     */
    public void LocalArrayLift() {
        int liftId = 0;
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock block : function.getBasicBlocks()) {
                Iterator<Instruction> it = block.getInstructions().iterator();
                while (it.hasNext()) {
                    Instruction instr = it.next();
                    if (!(instr instanceof AllocaInstr)) {
                        continue;
                    }
                    AllocaInstr alloca = (AllocaInstr) instr;
                    IRType pt = alloca.getType().ptTo();
                    if (pt == null || !pt.isArray()) {
                        continue;
                    }
                    if (!alloca.isConst) {
                        continue;
                    }
                    if (isArrayWritten(function, alloca)) {
                        continue;
                    }

                    String gName = function.getName() + ".liftarr." + liftId;
                    liftId++;
                    GlobalVariable gv = new GlobalVariable(gName, new IRType("ptr", pt));
                    gv.setIsConst(true);

                    int len = pt.size;
                    ArrayList<Value> init = new ArrayList<>();
                    ArrayList<Value> src = alloca.getValues();
                    for (int i = 0; i < len; i++) {
                        Value v = (src != null && i < src.size()) ? src.get(i) : null;
                        if (v == null || v.getValue() == null) {
                            v = new ConstantInt(0);
                        }
                        init.add(v);
                    }
                    gv.setValue(init);
                    irModule.addGlobal(gv);

                    ArrayList<Value> users = new ArrayList<>(alloca.getUsers());
                    for (Value u : users) {
                        if (u instanceof User) {
                            ((User) u).changeUse(alloca, gv);
                            alloca.rmUser((User) u);
                        }
                    }
                    it.remove();
                }
            }
        }
    }

    private boolean isArrayWritten(Function function, AllocaInstr alloca) {
        for (BasicBlock b : function.getBasicBlocks()) {
            for (Instruction ins : b.getInstructions()) {
                if (ins instanceof StoreInstr) {
                    Value dst = ins.getUseValue(1);
                    if (dst == alloca) {
                        return true;
                    }
                    if (dst instanceof GepInstr) {
                        Value base = ((GepInstr) dst).getUseValue(0);
                        if (base == alloca) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * DeadBranchEliminate: fold constant branches and drop unreachable basic blocks.
     * - If branch condition is constant, redirect to the taken target and ignore the other.
     * - If true/false targets are identical, treat as unconditional.
     * - Rebuild CFG and prune blocks unreachable from entry.
     */
    public void DeadBranchEliminate() {
        for (Function f : irModule.getFunctions()) {
            // 1) Fold constant branches to a single successor.
            for (BasicBlock b : f.getBasicBlocks()) {
                ArrayList<Instruction> instrs = b.getInstructions();
                if (instrs.isEmpty()) continue;
                Instruction term = instrs.get(instrs.size() - 1);
                if (!(term instanceof BranchInstr)) continue;

                BranchInstr br = (BranchInstr) term;
                Value cond = br.getUseValue(0);
                Constant c = cond.getValue();

                BasicBlock t = br.getTrueBlock();
                BasicBlock fblk = br.getFalseBlock();

                if (c instanceof ConstantInt) {
                    int v = Integer.parseInt(c.getName());
                    if (v == 0) {
                        br.setUseValue(0, new ConstantInt(0));
                        br.setUseValue(1, fblk);
                        br.setUseValue(2, fblk);
                        removePhiIncoming(t, b);
                    } else {
                        br.setUseValue(0, new ConstantInt(1));
                        br.setUseValue(2, t);
                        br.setUseValue(1, t);
                        removePhiIncoming(fblk, b);
                    }
                } else if (t == fblk) {
                    br.setUseValue(0, new ConstantInt(1));
                }
            }

            // 2) Rebuild CFG edges from terminals.
            rebuildCFGEdges(f);

            // 3) Remove unreachable blocks.
            ArrayList<BasicBlock> unreachable = findUnreachable(f);
            for (BasicBlock ub : unreachable) {
                removeBlockAndPhiRefs(f, ub);
            }
            if (!unreachable.isEmpty()) {
                rebuildCFGEdges(f);
            }
        }
    }

    private void rebuildCFGEdges(Function f) {
        for (BasicBlock b : f.getBasicBlocks()) {
            b.next.clear();
            b.prev.clear();
        }
        for (BasicBlock b : f.getBasicBlocks()) {
            ArrayList<Instruction> instrs = b.getInstructions();
            if (instrs.isEmpty()) continue;
            Instruction term = instrs.get(instrs.size() - 1);
            if (term instanceof BranchInstr) {
                BasicBlock t = ((BranchInstr) term).getTrueBlock();
                BasicBlock fl = ((BranchInstr) term).getFalseBlock();
                b.addNextBlock(t);
                b.addNextBlock(fl);
                t.addPrevBlock(b);
                fl.addPrevBlock(b);
            } else if (term instanceof JumpInstr) {
                BasicBlock nx = ((JumpInstr) term).getBlock();
                b.addNextBlock(nx);
                nx.addPrevBlock(b);
            }
        }
    }

    private ArrayList<BasicBlock> findUnreachable(Function f) {
        ArrayList<BasicBlock> res = new ArrayList<>();
        if (f.getBasicBlocks().isEmpty()) return res;
        HashSet<BasicBlock> vis = new HashSet<>();
        ArrayList<BasicBlock> q = new ArrayList<>();
        BasicBlock entry = f.getBasicBlocks().get(0);
        q.add(entry);
        vis.add(entry);
        for (int i = 0; i < q.size(); i++) {
            BasicBlock cur = q.get(i);
            for (BasicBlock nx : cur.next) {
                if (!vis.contains(nx)) {
                    vis.add(nx);
                    q.add(nx);
                }
            }
        }
        for (BasicBlock b : new ArrayList<>(f.getBasicBlocks())) {
            if (!vis.contains(b)) {
                res.add(b);
            }
        }
        return res;
    }

    private void removeBlockAndPhiRefs(Function f, BasicBlock ub) {
        // Remove incoming references in successors' Phi nodes.
        for (BasicBlock succ : new ArrayList<>(ub.next)) {
            removePhiIncoming(succ, ub);
            succ.removePrevBlock(ub);
        }
        for (BasicBlock pred : new ArrayList<>(ub.prev)) {
            pred.removeNextBlock(ub);
        }
        f.removeBlock(ub);
    }

    private void removePhiIncoming(BasicBlock block, BasicBlock pred) {
        ArrayList<Instruction> instrs = block.getInstructions();
        for (Instruction ins : instrs) {
            if (!(ins instanceof PhiInstr)) break;
            PhiInstr phi = (PhiInstr) ins;
            ArrayList<BasicBlock> defs = phi.defBlock;
            for (int i = defs.size() - 1; i >= 0; i--) {
                if (defs.get(i).equals(pred)) {
                    defs.remove(i);
                    // remove operand i
                    Value v = phi.getUseValue(i);
                    if (v != null) {
                        v.rmUser(phi);
                    }
                    phi.getUseList().remove(i);
                    phi.values.remove(i);
                }
            }
        }
    }

    /**
     * RemoveBlocks: prune unreachable blocks and orphan blocks after previous cleanups.
     * Steps: rebuild CFG edges, BFS from entry, remove non-reachable blocks and clean Phi incoming edges.
     */
    public void RemoveBlocks() {
        for (Function f : irModule.getFunctions()) {
            if (f.getBasicBlocks().isEmpty()) continue;

            rebuildCFGEdges(f);

            HashSet<BasicBlock> vis = new HashSet<>();
            ArrayList<BasicBlock> q = new ArrayList<>();
            BasicBlock entry = f.getBasicBlocks().get(0);
            q.add(entry);
            vis.add(entry);
            for (int i = 0; i < q.size(); i++) {
                BasicBlock cur = q.get(i);
                for (BasicBlock nx : cur.next) {
                    if (!vis.contains(nx)) {
                        vis.add(nx);
                        q.add(nx);
                    }
                }
            }

            ArrayList<BasicBlock> dead = new ArrayList<>();
            for (BasicBlock b : new ArrayList<>(f.getBasicBlocks())) {
                if (!vis.contains(b)) {
                    dead.add(b);
                }
            }
            for (BasicBlock b : dead) {
                removeBlockAndPhiRefs(f, b);
            }
            if (!dead.isEmpty()) {
                rebuildCFGEdges(f);
            }
        }
    }

    /**
     * DeadLoopEliminate: remove loops that have no observable effect (no store/call/side effects) and whose
     * exit does not depend on external state (fixed iteration known to terminate but results unused).
     * Conservative heuristic:
     * - Loop detected by a backedge (block that dominates one of its successors).
     * - All instructions inside loop are side-effect free (no Store, no Call, no phi with external use).
     * - Values produced in loop are not used outside the loop.
     */
    public void DeadLoopEliminate() {
        // Refresh dominators once before scanning all functions.
        initDominate();
        for (Function f : irModule.getFunctions()) {
            if (f.getBasicBlocks().isEmpty()) continue;
            HashSet<BasicBlock> toRemove = new HashSet<>();
            for (BasicBlock b : f.getBasicBlocks()) {
                for (BasicBlock nx : b.next) {
                    if (nx.isDominate(b)) { // backedge b -> nx forming a loop
                        HashSet<BasicBlock> loopBlocks = collectLoop(b, nx);
                        if (loopBlocks.contains(f.getBasicBlocks().get(0))) {
                            continue; // never remove entry block loops conservatively
                        }
                        if (isLoopSideEffectFree(loopBlocks) && loopDefsNotUsedOutside(loopBlocks, f)) {
                            toRemove.addAll(loopBlocks);
                        }
                    }
                }
            }
            if (toRemove.isEmpty()) continue;
            for (BasicBlock lb : toRemove) {
                removeBlockAndPhiRefs(f, lb);
            }
            rebuildCFGEdges(f);
        }
    }

    private HashSet<BasicBlock> collectLoop(BasicBlock tail, BasicBlock header) {
        HashSet<BasicBlock> loop = new HashSet<>();
        ArrayList<BasicBlock> stack = new ArrayList<>();
        stack.add(tail);
        while (!stack.isEmpty()) {
            BasicBlock cur = stack.remove(stack.size() - 1);
            if (!loop.add(cur)) continue;
            for (BasicBlock p : cur.prev) {
                if (p == header || header.isDominate(p)) {
                    stack.add(p);
                }
            }
        }
        loop.add(header);
        return loop;
    }

    private boolean isLoopSideEffectFree(HashSet<BasicBlock> loop) {
        for (BasicBlock b : loop) {
            for (Instruction ins : b.getInstructions()) {
                if (ins instanceof StoreInstr) return false;
                if (ins instanceof CallInstr) return false;
                if (ins instanceof BranchInstr || ins instanceof JumpInstr || ins instanceof PhiInstr) {
                    continue;
                }
                // Other instructions are assumed pure.
            }
        }
        return true;
    }

    private boolean loopDefsNotUsedOutside(HashSet<BasicBlock> loop, Function f) {
        for (BasicBlock b : loop) {
            for (Instruction ins : b.getInstructions()) {
                for (Value u : ins.getUsers()) {
                    if (u instanceof Instruction) {
                        BasicBlock ub = f.findInstrBlock((Instruction) u);
                        if (ub != null && !loop.contains(ub)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * DeadRetEliminate: for calls whose return value is unused, avoid generating return storage.
     * We keep the call (side effects) but drop its result handling.
     */
    public void DeadRetEliminate() {
        for (Function f : irModule.getFunctions()) {
            for (BasicBlock b : f.getBasicBlocks()) {
                for (Instruction ins : b.getInstructions()) {
                    if (ins instanceof CallInstr) {
                        CallInstr call = (CallInstr) ins;
                        if (call.getUserList().isEmpty()) {
                            call.discardReturn();
                        }
                    }
                }
            }
        }
    }

    /**
     * DeadArgEliminate: remove parameters that are not used in function body and update call sites.
     * Scope: skip built-in I/O functions (putint/putstr/putch/getint).
     */
    public void DeadArgEliminate() {
        ArrayList<Function> funcs = irModule.getFunctions();
        for (Function f : funcs) {
            if (isBuiltinFunc(f)) {
                continue;
            }
            ArrayList<Value> params = new ArrayList<>(f.getParameters());
            ArrayList<Integer> deadIdx = new ArrayList<>();
            for (int i = 0; i < params.size(); i++) {
                Value p = params.get(i);
                if (p.getUserList().isEmpty()) {
                    deadIdx.add(i);
                }
            }
            if (deadIdx.isEmpty()) {
                continue;
            }

            // Update call sites: remove args in descending index order.
            for (Function g : funcs) {
                for (BasicBlock b : g.getBasicBlocks()) {
                    for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                        if (ins instanceof CallInstr) {
                            CallInstr call = (CallInstr) ins;
                            Value callee = call.getUseValue(0);
                            if (callee == f) {
                                for (int k = deadIdx.size() - 1; k >= 0; k--) {
                                    call.removeArg(deadIdx.get(k));
                                }
                            }
                        }
                    }
                }
            }

            // Remove params from function signature in descending order.
            for (int k = deadIdx.size() - 1; k >= 0; k--) {
                f.removeParameter(deadIdx.get(k));
            }
        }
    }

    private boolean isBuiltinFunc(Function f) {
        String n = f.getName();
        return n.equals("putint") || n.equals("putstr") || n.equals("putch") || n.equals("getint");
    }

    /**
     * SoraPass: scalarize small, immutable local arrays accessed only by constant indices in entry block.
     * Assumptions for safety:
     * - Alloca array (fixed length) in the function.
     * - All stores to this array happen in the entry block via GEP with constant index.
     * - No other writes and no address escapes (only GEP->load/store users).
     * Transformation: replace loads with the SSA value stored for that index (or zero if never stored),
     * remove corresponding loads/stores/GEPs/alloca.
     */
    public void SoraPass() {
        for (Function function : irModule.getFunctions()) {
            if (function.getBasicBlocks().isEmpty()) {
                continue;
            }
            BasicBlock entry = function.getBasicBlocks().get(0);

            ArrayList<AllocaInstr> arrays = new ArrayList<>();
            for (BasicBlock b : function.getBasicBlocks()) {
                for (Instruction ins : b.getInstructions()) {
                    if (ins instanceof AllocaInstr) {
                        IRType pt = ((AllocaInstr) ins).getType().ptTo();
                        if (pt != null && pt.isArray() && pt.size > 0) {
                            arrays.add((AllocaInstr) ins);
                        }
                    }
                }
            }

            for (AllocaInstr alloca : arrays) {
                if (!canScalarizeArray(function, entry, alloca)) {
                    continue;
                }

                int len = alloca.getType().ptTo().size;
                ArrayList<Value> idxValue = new ArrayList<>(len);
                for (int i = 0; i < len; i++) idxValue.add(new ConstantInt(0));

                // Record last store per index in entry order.
                for (Instruction ins : entry.getInstructions()) {
                    if (!(ins instanceof StoreInstr)) continue;
                    Value dst = ins.getUseValue(1);
                    if (!(dst instanceof GepInstr)) continue;
                    GepInstr gep = (GepInstr) dst;
                    if (gep.getUseValue(0) != alloca) continue;
                    Constant cidx = gep.getUseValue(1).getValue();
                    if (cidx == null) continue;
                    int id = Integer.parseInt(cidx.getName());
                    if (id < 0 || id >= len) continue;
                    idxValue.set(id, ins.getUseValue(0));
                }

                // Replace loads and mark stores/geps for removal.
                ArrayList<Instruction> removeList = new ArrayList<>();
                for (BasicBlock b : function.getBasicBlocks()) {
                    for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                        if (ins instanceof LoadInstr) {
                            Value src = ins.getUseValue(0);
                            if (src instanceof GepInstr && ((GepInstr) src).getUseValue(0) == alloca) {
                                Constant cidx = ((GepInstr) src).getUseValue(1).getValue();
                                if (cidx == null) continue;
                                int id = Integer.parseInt(cidx.getName());
                                if (id < 0 || id >= len) continue;
                                Value repl = idxValue.get(id);
                                for (Value u : ins.getUsers()) {
                                    ((User) u).changeUse(ins, repl);
                                }
                                removeList.add(ins);
                            }
                        }
                        else if (ins instanceof StoreInstr) {
                            Value dst = ins.getUseValue(1);
                            if (dst instanceof GepInstr && ((GepInstr) dst).getUseValue(0) == alloca) {
                                removeList.add(ins);
                            }
                        }
                    }
                }

                for (Instruction rm : removeList) {
                    BasicBlock b = function.findInstrBlock(rm);
                    if (b != null) {
                        removeInstrAndUnlink(b, rm);
                    }
                }

                // Remove dead GEPs tied to this alloca.
                for (BasicBlock b : function.getBasicBlocks()) {
                    ArrayList<Instruction> deadGep = new ArrayList<>();
                    for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                        if (ins instanceof GepInstr && ((GepInstr) ins).getUseValue(0) == alloca && ins.getUsers().isEmpty()) {
                            deadGep.add(ins);
                        }
                    }
                    for (Instruction rm : deadGep) {
                        removeInstrAndUnlink(b, rm);
                    }
                }

                // Remove the alloca if unused.
                if (alloca.getUserList().isEmpty()) {
                    BasicBlock b = function.findInstrBlock(alloca);
                    if (b != null) {
                        removeInstrAndUnlink(b, alloca);
                    }
                }
            }
        }
    }

    /**
     * FoldConstLocalArray:
     * Fold local 1-D arrays into constants if they behave like a constant table.
     *
     * Accepted patterns (more aggressive than before):
     * - alloca [N x i32]
     * - optional constant initialization via stores in the entry block:
     *     store C, gep(alloca, constIdx)
     * - no stores to the array outside the entry block
     * - no address escapes: the alloca is only used by GEP, and each such GEP is only used by load/store
     * - all indices are constant and in range
     *
     * Transformation:
     * - Replace each load (gep(alloca, i)) with the known constant element value (default 0).
     * - Remove the now-dead loads/stores/geps and the alloca.
     */
    public void FoldConstLocalArray() {
        for (Function function : irModule.getFunctions()) {
            if (function.getBasicBlocks().isEmpty()) {
                continue;
            }
            BasicBlock entry = function.getBasicBlocks().get(0);

            ArrayList<AllocaInstr> candidates = new ArrayList<>();
            for (BasicBlock b : function.getBasicBlocks()) {
                for (Instruction ins : b.getInstructions()) {
                    if (!(ins instanceof AllocaInstr)) {
                        continue;
                    }
                    AllocaInstr a = (AllocaInstr) ins;
                    IRType pt = a.getType().ptTo();
                    if (pt != null && pt.isArray()) {
                        candidates.add(a);
                    }
                }
            }

            for (AllocaInstr alloca : candidates) {
                if (!canFoldConstLocalArray(function, entry, alloca)) {
                    continue;
                }

                int len = alloca.getType().ptTo().size;
                ArrayList<Value> constElem = new ArrayList<>(len);
                for (int i = 0; i < len; i++) {
                    constElem.add(new ConstantInt(0));
                }

                // Prefer explicit element list if present (usually for const decl arrays).
                ArrayList<Value> preset = alloca.getValues();
                if (preset != null && !preset.isEmpty()) {
                    for (int i = 0; i < len; i++) {
                        Value v = (i < preset.size()) ? preset.get(i) : null;
                        Value c = asConstantValue(v);
                        if (c != null) {
                            constElem.set(i, c);
                        }
                    }
                }

                // Apply entry-block constant stores (override preset) in program order.
                ArrayList<Instruction> removeStores = new ArrayList<>();
                for (Instruction ins : new ArrayList<>(entry.getInstructions())) {
                    if (!(ins instanceof StoreInstr)) {
                        continue;
                    }
                    Value dst = ins.getUseValue(1);
                    if (!(dst instanceof GepInstr)) {
                        continue;
                    }
                    GepInstr gep = (GepInstr) dst;
                    if (gep.getUseValue(0) != alloca) {
                        continue;
                    }
                    Constant idxC = gep.getUseValue(1).getValue();
                    if (!(idxC instanceof ConstantInt)) {
                        continue;
                    }
                    int idx = Integer.parseInt(idxC.getName());
                    if (idx < 0 || idx >= len) {
                        continue;
                    }
                    Value c = asConstantValue(ins.getUseValue(0));
                    if (c == null) {
                        // Should not happen due to canFoldConstLocalArray check, but stay safe.
                        continue;
                    }
                    constElem.set(idx, c);
                    removeStores.add(ins);
                }

                // 1) Replace loads with constant elements.
                ArrayList<Instruction> removeLoads = new ArrayList<>();
                for (BasicBlock b : function.getBasicBlocks()) {
                    for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                        if (!(ins instanceof LoadInstr)) {
                            continue;
                        }
                        Value from = ins.getUseValue(0);
                        if (!(from instanceof GepInstr)) {
                            continue;
                        }
                        GepInstr gep = (GepInstr) from;
                        if (gep.getUseValue(0) != alloca) {
                            continue;
                        }
                        Constant idxConst = gep.getUseValue(1).getValue();
                        if (!(idxConst instanceof ConstantInt)) {
                            continue;
                        }
                        int idx = Integer.parseInt(idxConst.getName());
                        if (idx < 0 || idx >= len) {
                            continue;
                        }
                        Value elem = constElem.get(idx);
                        replaceAllUses(ins, elem);
                        removeLoads.add(ins);
                    }
                }
                for (Instruction ld : removeLoads) {
                    BasicBlock b = function.findInstrBlock(ld);
                    if (b != null) {
                        removeInstrAndUnlink(b, ld);
                    }
                }

                // Remove stores that only existed for initializing this constant table.
                for (Instruction st : removeStores) {
                    BasicBlock b = function.findInstrBlock(st);
                    if (b != null) {
                        removeInstrAndUnlink(b, st);
                    }
                }

                // 2) Remove now-unused GEPs derived from this alloca.
                boolean changed;
                do {
                    changed = false;
                    for (BasicBlock b : function.getBasicBlocks()) {
                        for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                            if (!(ins instanceof GepInstr)) {
                                continue;
                            }
                            GepInstr gep = (GepInstr) ins;
                            if (gep.getUseValue(0) != alloca) {
                                continue;
                            }
                            if (!isValueReferencedInFunction(function, gep)) {
                                removeInstrAndUnlink(b, gep);
                                changed = true;
                            }
                        }
                    }
                } while (changed);

                // 3) Remove the alloca itself if no longer referenced.
                if (!isValueReferencedInFunction(function, alloca)) {
                    BasicBlock b = function.findInstrBlock(alloca);
                    if (b != null) {
                        removeInstrAndUnlink(b, alloca);
                    }
                }
            }
        }
    }

    private boolean canFoldConstLocalArray(Function function, BasicBlock entry, AllocaInstr alloca) {
        IRType pt = alloca.getType().ptTo();
        if (pt == null || !pt.isArray()) {
            return false;
        }
        int len = pt.size;
        if (len <= 0) {
            return false;
        }

        for (BasicBlock b : function.getBasicBlocks()) {
            for (Instruction ins : b.getInstructions()) {
                // No address escape: alloca must only appear as the base of GEP.
                for (Value op : ins.getOperands()) {
                    if (op != alloca) {
                        continue;
                    }
                    if (!(ins instanceof GepInstr)) {
                        return false;
                    }
                    if (((GepInstr) ins).getUseValue(0) != alloca) {
                        return false;
                    }
                }

                // All GEP indices must be constant and in range; their users must be load/store only.
                if (ins instanceof GepInstr) {
                    GepInstr gep = (GepInstr) ins;
                    if (gep.getUseValue(0) == alloca) {
                        Constant idxConst = gep.getUseValue(1).getValue();
                        if (!(idxConst instanceof ConstantInt)) {
                            return false;
                        }
                        int idx = Integer.parseInt(idxConst.getName());
                        if (idx < 0 || idx >= len) {
                            return false;
                        }
                        for (Value uv : gep.getUsers()) {
                            if (!(uv instanceof LoadInstr) && !(uv instanceof StoreInstr)) {
                                return false;
                            }
                        }
                    }
                }

                // No stores outside entry; and entry stores must write constant values.
                if (ins instanceof StoreInstr) {
                    Value dst = ins.getUseValue(1);
                    if (dst == alloca) {
                        return false;
                    }
                    if (dst instanceof GepInstr && ((GepInstr) dst).getUseValue(0) == alloca) {
                        if (b != entry) {
                            return false;
                        }
                        if (asConstantValue(ins.getUseValue(0)) == null) {
                            return false;
                        }
                    }
                }
            }
        }

        // If there is no preset element list and no entry stores, folding doesn't help.
        boolean hasPreset = alloca.getValues() != null && !alloca.getValues().isEmpty();
        boolean hasEntryStore = false;
        for (Instruction ins : entry.getInstructions()) {
            if (ins instanceof StoreInstr) {
                Value dst = ins.getUseValue(1);
                if (dst instanceof GepInstr && ((GepInstr) dst).getUseValue(0) == alloca) {
                    hasEntryStore = true;
                    break;
                }
            }
        }
        return hasPreset || hasEntryStore;
    }

    private Value asConstantValue(Value v) {
        if (v == null) {
            return null;
        }
        Constant c = v.getValue();
        if (c == null) {
            return null;
        }
        return c;
    }

    private boolean isValueReferencedInFunction(Function function, Value v) {
        for (BasicBlock b : function.getBasicBlocks()) {
            for (Instruction ins : b.getInstructions()) {
                for (Value op : ins.getOperands()) {
                    if (op == v) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void replaceAllUses(Value from, Value to) {
        if (from == null || to == null || from == to) {
            return;
        }
        ArrayList<Value> users = new ArrayList<>(from.getUsers());
        for (Value uv : users) {
            if (!(uv instanceof User)) {
                continue;
            }
            User u = (User) uv;
            boolean touched = false;
            for (Use use : u.getUseList()) {
                if (use.getValue() == from) {
                    use.setValue(to);
                    touched = true;
                }
            }
            if (!touched) {
                continue;
            }
            for (int i = 0; i < u.values.size(); i++) {
                if (u.values.get(i) == from) {
                    u.values.set(i, to);
                }
            }
            to.addUser(u);
            from.rmUser(u);
        }
    }

    private boolean canScalarizeArray(Function function, BasicBlock entry, AllocaInstr alloca) {
        // Reject if any non-GEP use or address escape.
        for (BasicBlock b : function.getBasicBlocks()) {
            for (Instruction ins : b.getInstructions()) {
                ArrayList<Value> ops = ins.getOperands();
                boolean touches = false;
                for (Value v : ops) {
                    if (v == alloca) {
                        touches = true;
                        break;
                    }
                }
                if (!touches) {
                    continue;
                }

                if (ins instanceof GepInstr) {
                    GepInstr gep = (GepInstr) ins;
                    if (gep.getUseValue(0) != alloca) {
                        return false;
                    }
                    Constant cidx = gep.getUseValue(1).getValue();
                    if (cidx == null) {
                        return false;
                    }
                    int id = Integer.parseInt(cidx.getName());
                    int len = alloca.getType().ptTo().size;
                    if (id < 0 || id >= len) {
                        return false;
                    }
                    // Users of this GEP must be load or store.
                    for (Value u : gep.getUsers()) {
                        if (!(u instanceof LoadInstr || u instanceof StoreInstr)) {
                            return false;
                        }
                    }
                }
                else if (ins instanceof StoreInstr) {
                    // Direct store to alloca without GEP -> not supported.
                    Value dst = ins.getUseValue(1);
                    if (dst == alloca) {
                        return false;
                    }
                }
                else if (ins instanceof LoadInstr) {
                    // Direct load from alloca -> not supported.
                    Value src = ins.getUseValue(0);
                    if (src == alloca) {
                        return false;
                    }
                }
                else {
                    // Any other use (call/phi/etc) is escape.
                    return false;
                }
            }
        }

        // All stores must be in entry block.
        for (BasicBlock b : function.getBasicBlocks()) {
            for (Instruction ins : b.getInstructions()) {
                if (!(ins instanceof StoreInstr)) continue;
                Value dst = ins.getUseValue(1);
                if (dst instanceof GepInstr && ((GepInstr) dst).getUseValue(0) == alloca) {
                    if (b != entry) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * DeadLocalArrayEliminate: delete local array allocas that are never read.
     *
     * Definition of "unused" here:
     * - The alloca allocates an array type (stack local array).
     * - Its address does NOT escape the function (only used through GEP/Load/Store chains).
     * - There is no Load reachable from the alloca through any number of GEPs.
     *
     * If an array is only written (stores to the alloca or derived GEPs) and never read,
     * those stores are dead and can be removed; then dead GEPs and finally the alloca itself
     * are removed when they become unused.
     */
    public void DeadLocalArrayEliminate() {
        for (Function function : irModule.getFunctions()) {
            ArrayList<AllocaInstr> arrays = new ArrayList<>();
            for (BasicBlock b : function.getBasicBlocks()) {
                for (Instruction ins : b.getInstructions()) {
                    if (!(ins instanceof AllocaInstr)) {
                        continue;
                    }
                    AllocaInstr alloca = (AllocaInstr) ins;
                    IRType pt = alloca.getType().ptTo();
                    if (pt != null && pt.isArray()) {
                        arrays.add(alloca);
                    }
                }
            }

            for (AllocaInstr alloca : arrays) {
                // Skip arrays whose address may be observed outside the function.
                if (isAllocaEscaping(alloca)) {
                    continue;
                }
                // Skip arrays that have any load through the (alloca -> GEP* -> load) chain.
                if (isArrayReadThroughGepChain(alloca)) {
                    continue;
                }
                removeDeadLocalArray(function, alloca);
            }
        }
    }

    private boolean isArrayReadThroughGepChain(AllocaInstr alloca) {
        ArrayList<Value> worklist = new ArrayList<>();
        HashSet<Value> vis = new HashSet<>();
        worklist.add(alloca);
        vis.add(alloca);

        while (!worklist.isEmpty()) {
            Value cur = worklist.remove(worklist.size() - 1);
            for (Value user : cur.getUsers()) {
                if (user instanceof GepInstr) {
                    GepInstr g = (GepInstr) user;
                    if (g.getUseValue(0) != cur) {
                        // Unexpected use pattern; treat as observable.
                        return true;
                    }
                    if (!vis.contains(user)) {
                        vis.add(user);
                        worklist.add(user);
                    }
                }
                else if (user instanceof LoadInstr) {
                    // A load from this pointer chain means the array is used.
                    if (((LoadInstr) user).getUseValue(0) == cur) {
                        return true;
                    }
                    return true;
                }
                else if (user instanceof StoreInstr) {
                    // Store to this pointer chain is a write, not a read.
                    StoreInstr st = (StoreInstr) user;
                    // If the pointer chain appears as the stored VALUE, it is observable/escaping.
                    if (st.getUseValue(0) == cur) {
                        return true;
                    }
                    // If it is the destination, keep scanning.
                    if (st.getUseValue(1) != cur) {
                        return true;
                    }
                }
                else {
                    // Any other user means the value is observed (call/phi/etc).
                    return true;
                }
            }
        }
        return false;
    }

    private void removeDeadLocalArray(Function function, AllocaInstr alloca) {
        // 1) Remove stores writing to this array (directly or through GEP chains).
        HashMap<Instruction, BasicBlock> toRemove = new HashMap<>();
        for (BasicBlock b : function.getBasicBlocks()) {
            for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                if (ins instanceof StoreInstr) {
                    Value dst = ins.getUseValue(1);
                    AllocaInstr base = getPointerAllocaBase(dst);
                    if (base == alloca) {
                        toRemove.put(ins, b);
                    }
                }
                else if (ins instanceof LoadInstr) {
                    // Safety: if any load still exists, the array isn't dead.
                    Value src = ins.getUseValue(0);
                    AllocaInstr base = getPointerAllocaBase(src);
                    if (base == alloca) {
                        return;
                    }
                }
            }
        }
        for (Map.Entry<Instruction, BasicBlock> e : toRemove.entrySet()) {
            removeInstrAndUnlink(e.getValue(), e.getKey());
        }

        // 2) Iteratively remove now-dead GEPs derived from this alloca.
        boolean changed;
        do {
            changed = false;
            for (BasicBlock b : function.getBasicBlocks()) {
                for (Instruction ins : new ArrayList<>(b.getInstructions())) {
                    if (ins instanceof GepInstr) {
                        AllocaInstr base = getPointerAllocaBase(ins);
                        if (base == alloca && ins.getUsers().isEmpty()) {
                            removeInstrAndUnlink(b, ins);
                            changed = true;
                        }
                    }
                }
            }
        } while (changed);

        // 3) Remove the alloca itself if it has no remaining users.
        if (alloca.getUserList().isEmpty()) {
            BasicBlock b = function.findInstrBlock(alloca);
            if (b != null) {
                removeInstrAndUnlink(b, alloca);
            }
        }
    }

    private AllocaInstr getPointerAllocaBase(Value ptr) {
        Value cur = ptr;
        while (cur instanceof GepInstr) {
            cur = ((GepInstr) cur).getUseValue(0);
        }
        if (cur instanceof AllocaInstr) {
            return (AllocaInstr) cur;
        }
        return null;
    }

    /**
     * ConstIdx2Value: fold array accesses with constant index into the element value.
     * Pattern: load (gep base, constIdx) where base is a const global/alloca array with known elements.
     * Action: replace uses of the load with the pre-known element value and remove the load (and dead gep).
     */
    public void ConstIdx2Value() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock block : function.getBasicBlocks()) {
                ArrayList<Instruction> toRemove = new ArrayList<>();

                for (Instruction instr : block.getInstructions()) {
                    if (!(instr instanceof LoadInstr)) {
                        continue;
                    }
                    Value from = instr.getUseValue(0);
                    if (!(from instanceof GepInstr)) {
                        continue;
                    }
                    GepInstr gep = (GepInstr) from;
                    Constant idxConst = gep.getUseValue(1).getValue();
                    if (idxConst == null) {
                        continue;
                    }

                    Value base = gep.getUseValue(0);
                    Value elem = null;
                    if (base instanceof AllocaInstr) {
                        elem = ((AllocaInstr) base).getKthEle(idxConst);
                    } else if (base instanceof GlobalVariable) {
                        elem = ((GlobalVariable) base).getKthEle(idxConst);
                    }
                    if (elem == null) {
                        continue;
                    }

                    for (Value userV : instr.getUsers()) {
                        ((User) userV).changeUse(instr, elem);
                    }
                    toRemove.add(instr);
                }

                for (Instruction rm : toRemove) {
                    removeInstrAndUnlink(block, rm);
                }

                // Clean up dead GEPs that became unused after load removal.
                ArrayList<Instruction> deadGeps = new ArrayList<>();
                for (Instruction instr : new ArrayList<>(block.getInstructions())) {
                    if (instr instanceof GepInstr && instr.getUsers().isEmpty()) {
                        deadGeps.add(instr);
                    }
                }
                for (Instruction rm : deadGeps) {
                    removeInstrAndUnlink(block, rm);
                }
            }
        }
    }

    public void insertPhi() {
        for (Function function : irModule.getFunctions()) {
            ArrayList<PhiInstr> phiInstrs = new ArrayList<>();
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                for (Instruction instruction : basicBlock.getInstructions()) {
                    if (instruction instanceof AllocaInstr &&
                        instruction.getType().ptTo().toString().equals("i32")) {
                        phiInstrs.addAll(insertPhiOfVar(function, (AllocaInstr) instruction));
                    }
                }
            }
            for (PhiInstr phiInstr : phiInstrs) {
                phiInstr.getBlock().insertPhi(phiInstr);
            }
        }
    }

    public ArrayList<PhiInstr> insertPhiOfVar(Function function, AllocaInstr instr) {
        ArrayList<PhiInstr> ans = new ArrayList<>();
        basicBlocks = function.getBasicBlocks();
        HashSet<BasicBlock> Def = new HashSet<>();
        HashSet<BasicBlock> W = new HashSet<>();
        HashSet<BasicBlock> F = new HashSet<>();
        for (BasicBlock basicBlock : basicBlocks) {
            for (Instruction instruction : basicBlock.getInstructions()) {
                if (instruction.isDef(instr)) {
                    W.add(basicBlock);
                    Def.add(basicBlock);
                    break;
                }
            }
        }
        while (!W.isEmpty()) {
            BasicBlock block = W.iterator().next();
            W.remove(block);
            for (BasicBlock Y : block.dominanceFrontier) {
                if (!F.contains(Y)) {
                    PhiInstr phiInstr = new PhiInstr(instr, Y);
                    instr.store(Y, phiInstr);
                    ans.add(phiInstr);
                    F.add(Y);
                    if (!Def.contains(Y)) {
                        W.add(Y);
                    }
                }
            }
        }
        return ans;
    }

    public void initDF() {
        for (Function function : irModule.getFunctions()) {
            BasicBlock x = null;
            for (BasicBlock b : function.getBasicBlocks()) {
                for (BasicBlock a : b.prev) {
                    if (a == b) {
                        continue;
                    }
                    x = a;
                    while (!b.isStrictDominate(x)) {
                        x.addDF(b);
                        x = x.directDom;
                        if (x == null) {
                            break;
                        }
                    }
                }
            }
        }
    }

    public void initDominate() {
        for (Function f : irModule.getFunctions()) {
            for (BasicBlock b : f.getBasicBlocks()) {
                b.initDom(f.getBasicBlocks());
            }
            boolean flag = true;
            while (flag) {
                flag = false;
                for (BasicBlock b : f.getBasicBlocks()) {
                    boolean update = b.updateDom();
                    if (update) {
                        flag = true;
                    }
                }
            }
            for (BasicBlock b : f.getBasicBlocks()) {
                b.updateDirectDom();
            }
        }
    }

    public void buildCFG() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                for (Instruction instruction : basicBlock.getInstructions()) {
                    if (instruction instanceof JumpInstr) {
                        basicBlock.addNextBlock(((JumpInstr) instruction).getBlock());
                        ((JumpInstr) instruction).getBlock().addPrevBlock(basicBlock);
                    }
                    else if (instruction instanceof BranchInstr) {
                        BasicBlock trueBlock = ((BranchInstr) instruction).getTrueBlock();
                        BasicBlock falseBlock = ((BranchInstr) instruction).getFalseBlock();
                        basicBlock.addNextBlock(trueBlock);
                        basicBlock.addNextBlock(falseBlock);
                        trueBlock.addPrevBlock(basicBlock);
                        falseBlock.addPrevBlock(basicBlock);
                    }
                }
            }
        }
    }

    public void deadCodeCheck() {
        for (Function function : irModule.getFunctions()) {
            /*
            ArrayList<BasicBlock> rm = new ArrayList<>(function.getBasicBlocks());
            while (!rm.isEmpty()) {
                rm = new ArrayList<>();
                basicBlocks = function.getBasicBlocks();
                for (int i=1;i<basicBlocks.size();i++) {
                    if (basicBlocks.get(i).directDom == null) {
                        rm.add(basicBlocks.get(i));
                    }
                }
                for (BasicBlock block : rm) {
                    function.removeBlock(block);
                }
            }*/
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                instructions = basicBlock.getInstructions();
                for (int i = instructions.size() - 1; i >= 0; i--) {
                    Instruction instruction = instructions.get(i);
                    if (instruction.isDead()) {
                        basicBlock.rmInstruction(i);
                        int sz = instruction.getUseList().size();
                        for (int j = 0;j < sz; j++) {
                            instruction.getUseValue(j).rmUser(instruction);
                        }
                    }
                }
            }
        }
    }

    public void calGepCnt() {
        for (Function function : irModule.getFunctions()) {
            for (BasicBlock basicBlock : function.getBasicBlocks()) {
                for (Instruction instruction : basicBlock.getInstructions()) {
                    if (instruction instanceof GepInstr && gepCnt.containsKey((GepInstr) instruction)) {
                        int x = gepCnt.get(instruction);
                        gepCnt.put((GepInstr) instruction, x+1);
                    }
                    else if (instruction instanceof GepInstr) {
                        gepCnt.put((GepInstr) instruction, 1);
                    }
                }
            }
        }
    }

    public static boolean gepUseOneTime(GepInstr instr) {
        return gepCnt.containsKey(instr) && gepCnt.get(instr) == 1;
    }

    private void removeInstrAndUnlink(BasicBlock block, Instruction instr) {
        block.rmInstruction(instr);
        int sz = instr.getUseList().size();
        for (int i = 0; i < sz; i++) {
            Value v = instr.getUseValue(i);
            if (v != null) {
                v.rmUser(instr);
            }
        }
    }
}
