# java-diff-utils 调用链与不变量分析

分析对象：本仓库 `java-diff-utils` 模块（4.18-SNAPSHOT），`java-diff-utils-jgit` 只读不改。
所有行号基于当前工作区源码；路径均相对仓库根目录。

---

## 一、从 `DiffUtils.diff` 到 `Patch.applyTo` 的完整调用链

### 1. 入口重载与默认算法

默认算法的存放点只有一个静态字段：

- `java-diff-utils/src/main/java/com/github/difflib/DiffUtils.java:40`：
  `static DiffAlgorithmFactory DEFAULT_DIFF = MyersDiff.factory();`
- 修改入口唯一：`DiffUtils.withDefaultDiffAlgorithmFactory(...)`（`DiffUtils.java:47-49`），全局可变静态状态，无锁、无重置。
- 工厂接口：`java-diff-utils/src/main/java/com/github/difflib/algorithm/DiffAlgorithmFactory.java:26-28`，两个方法 `create()` / `create(BiPredicate)`。

七个 `diff` 重载的走向（行号都在 `DiffUtils.java`）：

| 重载 | 行 | 实际用谁 |
| --- | --- | --- |
| `diff(original, revised, progress)` | 60-63 | `DEFAULT_DIFF.create()` |
| `diff(original, revised)` | 73-75 | `DEFAULT_DIFF.create()` |
| `diff(original, revised, includeEqualParts)` | 86-88 | `DEFAULT_DIFF.create()` |
| `diff(String, String, progress)` | 98-100 | 调 60 行那个，最终也是 `DEFAULT_DIFF.create()` |
| `diff(source, target, equalizer)` | 114-120 | **见下面“坑”** |
| `diff(original, revised, algorithm, progress)` | 122-128 | 调用方传入的 `algorithm` |
| `diff(..., algorithm, progress, includeEqualParts)` | 142-153 | 调用方传入的 `algorithm`，所有重载的汇聚点 |
| `diff(original, revised, algorithm)` | 165-168 | 调用方传入的 `algorithm` |

所有重载最终都汇聚到 152 行：

```
Patch.generate(original, revised, algorithm.computeDiff(original, revised, progress), includeEqualParts)
```

即 `DiffUtils.java:152`（算法调用）→ `Patch.generate(...)`（`java-diff-utils/src/main/java/com/github/difflib/patch/Patch.java:301-348`）。

**一个真实的不等价坑（114-120 行）**：带 `equalizer` 的重载里，非空分支走 `DEFAULT_DIFF.create(equalizer)`（117 行，尊重全局默认工厂），但 `equalizer == null` 分支不是走 `DEFAULT_DIFF.create()`，而是硬编码 `new MyersDiff<>()`（119 行）。也就是说：一旦有人调用过 `withDefaultDiffAlgorithmFactory(MyersDiffWithLinearSpace.factory())` 或 jgit 的工厂，`diff(a, b, null)` 仍然拿到经典 `MyersDiff`，与其他所有重载不一致。“传 null 等于不传”这个直觉只在没人改过全局默认工厂时成立。实测（改默认为 linear space 后）：null 分支产生 `ChangeDelta`，`diff(a,b)` 路径产生 `InsertDelta`+`DeleteDelta`。

`diffInline`（`DiffUtils.java:180-195`）走 189 行的无 equalizer 默认路径，再在 190-193 行把每个字符 delta 的 chunk 行重新压成单元素列表，不产生新的 delta 类型。

### 2. equalizer 的传递链

经典 Myers（默认）：

- 字段：`java-diff-utils/src/main/java/com/github/difflib/algorithm/myers/MyersDiff.java:34` `private final BiPredicate ... equalizer;`
- 无参构造默认 `Object::equals`（`MyersDiff.java:36-38`）；带参构造在 41 行做非空校验后赋值（40-43 行）。
- 工厂 `MyersDiff.factory()`（`MyersDiff.java:187-199`）把两个 `create` 分别接到这两个构造器。
- 真正被调用的地方只有 snake 伸展的相等判断 `equalizer.test(orig.get(i), rev.get(j))`（`MyersDiff.java:117`）。它只决定“对角线上能不能继续滑动”，delta 的归类完全由路径端点坐标决定，不经过 equalizer。

linear-space Myers 结构相同：字段 `MyersDiffWithLinearSpace.java:35`，默认 `Object::equals`（37-39 行），构造校验 41-43 行；被调用四次：`buildScript` 递归终止格 83 行、向下/向上搜索的 snake 伸展 149 行和 173 行、`buildSnake` 192 行。

jgit 的 `HistogramDiff`（`java-diff-utils-jgit/src/main/java/com/github/difflib/algorithm/jgit/HistogramDiff.java`）**不支持 equalizer**：比较硬编码在 `DataListComparator.equals(...)` 的 `original.data.get(orgIdx).equals(revised.data.get(revIdx))`（`HistogramDiff.java:85`），`hash` 用 `hashCode()`（89-91 行）；该类也没有提供 `DiffAlgorithmFactory`。把它注册为全局默认后，`DiffUtils.diff(a, b, someEqualizer)` 会在 `DEFAULT_DIFF.create(equalizer)` 处失败（没有对应工厂），而无 equalizer 路径正常。

### 3. `Change` → delta 子类的归类位置

`Change` 只是四元坐标 + 类型（`java-diff-utils/src/main/java/com/github/difflib/algorithm/Change.java:24-46`）。

默认算法 `MyersDiff.buildRevision(...)` 沿 PathNode 回溯，在 `MyersDiff.java:169-175` 归类：

- 169-170 行：`ianchor == i && janchor != j` → `DeltaType.INSERT`；
- 171-172 行：`ianchor != i && janchor == j` → `DeltaType.DELETE`；
- 173-174 行：其余（两边都动）→ `DeltaType.CHANGE`。

回溯产生的 `changes` 是**逆序**的（从路径终点往起点，`MyersDiff.java:153-181`）。普通 `diff` 路径上 `Patch.generate(..., includeEquals=false)` 按接收顺序遍历（`Patch.java:314`），顺序真正被纠正是在任何取 delta 的地方调用 `getDeltas()` 时（见下）。

linear-space Myers 归类方式不同：`buildScript` 只发 `DELETE`（`MyersDiffWithLinearSpace.java:94`）和 `INSERT`（105 行），从不发 `CHANGE`，相邻同类变更在 91-99 / 102-110 行用 `withEndOriginal/withEndRevised` 合并。两边同时改的区域因此表现为“DELETE + INSERT 两个 delta”，这对 `applyTo` 没影响，但对 `applyFuzzy` 致命（见风险二）。

jgit `HistogramDiff` 的归类在 `HistogramDiff.java:50-64`：jgit 的 `DELETE/INSERT/REPLACE` 分别映射，且它会为相等段发 `DeltaType.EQUAL`（51 行初值）；`Patch.generate` 在 `includeEquals=false` 时 switch 的 `default`（`Patch.java:334-335`）把 EQUAL 静默丢弃。

`Patch.generate(...)`（`Patch.java:301-348`）把 `Change` 实例化成语义 delta：

- org/rev chunk 在 322-323 行由 `buildChunk`（297-299 行）按 `subList(start,end)` 切出；
- switch 在 324-335 行：DELETE→`DeleteDelta`（326 行）、INSERT→`InsertDelta`（329 行）、CHANGE→`ChangeDelta`（332 行），EQUAL 走空的 `default`。

**EQUAL 是谁造的、什么时候才有**：算法侧只有 `HistogramDiff` 会造 `EQUAL` 的 `Change`；两个 Myers 实现从不造。库内统一的 EQUAL 来源是 `Patch.generate` 的 316-320 行（变更前的相等段）和 341-345 行（尾部相等段），且只有 `includeEquals == true`（309-312 行先按 `startOriginal` 排序）才会插入 `EqualDelta`；`EqualDelta` 本体在 `java-diff-utils/src/main/java/com/github/difflib/patch/EqualDelta.java:24-42`，apply/restore/fuzzy 全是空操作（31、34、40-42 行）。`includeEqualParts=true` 的唯一公开入口是 `DiffUtils.java:86-88` → 152 行。unified diff 读取侧还有第三处 EQUAL 来源，见第二部分。

### 4. 从 Patch 到 `applyTo`

- 门面：`DiffUtils.patch(original, patch)`（`DiffUtils.java:205-207`）→ `Patch.applyTo`。
- `Patch.applyTo`（`Patch.java:57-61`）：58 行复制一份目标 list，59 行调 `applyToExisting`。
- `Patch.applyToExisting`（`Patch.java:71-80`）：
  - 72 行 `getDeltas().listIterator(deltas.size())` 取**反向**迭代器；
  - 73-74 行从尾向头遍历；
  - 75 行每个 delta 调 `AbstractDelta.verifyAndApplyTo(target)`；
  - 76-78 行结果非 OK 则交给 `conflictOutput.processConflict(...)`，默认实现 `CONFLICT_PRODUCES_EXCEPTION`（198-201 行）抛 `PatchFailedException`，可用 `withConflictOutput`（235-238 行）换成 git 风格冲突块（206-227 行）。
- `getDeltas()`（`Patch.java:283-286`）返回前在 284 行 `deltas.sort(comparing(d -> d.getSource().getPosition()))`——**升序是在这里保证的**，每次访问都就地排一次。反向遍历因此等价于“位置降序”。
- `AbstractDelta.verifyAndApplyTo`（`java-diff-utils/src/main/java/com/github/difflib/patch/AbstractDelta.java:61-67`）：62 行先用 **source** chunk 校验（57-59 行 `getSource().verifyChunk(target)`），63-65 行 OK 才调子类 `applyTo`。顺序不能换，见第四部分。
- 校验实现 `Chunk.verifyChunk`（`java-diff-utils/src/main/java/com/github/difflib/patch/Chunk.java:97-125`）：无 fuzz 版在 98 行调 `verifyChunk(target, 0, getPosition())`；110-125 行逐元素 `target.get(position + i).equals(lines.get(i))`（120 行），不等返回 `CONTENT_DOES_NOT_MATCH_TARGET`（121 行），越界返回 `POSITION_OUT_OF_TARGET`（116-118 行，这行有 bug，见风险一）。
- 各子类落地动作（坐标都是“当前这份 target 列表”里的下标）：
  - `ChangeDelta.applyTo`（`ChangeDelta.java:42-53`）：43 行取 `getSource().getPosition()`，45-47 行在该位置连续 `remove` source.size() 次，49-52 行在同位置插回 target 行；
  - `InsertDelta.applyTo`（`InsertDelta.java:39-45`）：40 行 source position（插入点），42-44 行 `target.add(position + i, line)`；
  - `DeleteDelta.applyTo`（`DeleteDelta.java:39-45`）：40-44 行在 source position 连续 remove；
  - `EqualDelta.applyTo`：空操作。

**多 delta 之间有没有累计偏移？没有，而且是刻意不累计**：所有 delta 都存“原文坐标”，`applyToExisting` 靠 72-74 行的降序遍历让“先落地的 delta 只改它后面的下标，碰不到还没处理的、位置更小的 delta”。delta 内部只使用自己 chunk 的固定 position（如 `ChangeDelta.java:43`），不存在 running offset 变量。把 72-74 行改成正向遍历时，插入类可能只是内容错位，删除/替换类会让后续 delta 在已被缩短的 list 上校验甚至直接越界（实证见新增测试与第四部分）。

反方向 `Patch.restore`（247-267 行）结构相同：248 行复制，`restoreToExisting` 在 262 行取反向迭代器、263-265 行降序调 `delta.restore(target)`；各子类 `restore` 用 **target** chunk 的 position（`ChangeDelta.java:57`、`InsertDelta.java:49`、`DeleteDelta.java:49`）。

`Patch.applyFuzzy`（102-121 行）是另一条路：**正向**遍历（108 行），靠显式累计的位移工作，细节见第二部分。

---

## 二、不变量清单（每条都落到行）

### A. chunk 坐标语义

1. `AbstractDelta` 持有的两个 chunk 中，`source` 是**原文（original/revised 输入中的 original 侧）坐标**，`target` 是**改后侧（revised 侧）坐标**。两个 chunk 在 `Patch.generate` 分别由 `change.startOriginal/endOriginal` 与 `change.startRevised/endRevised` 切出（`Patch.java:322-323`）；`Chunk.position` 是 `final`（`Chunk.java:40`、51-52 行赋值）。
2. `applyTo` 一律只看 source position：`ChangeDelta.java:43`、`InsertDelta.java:40`、`DeleteDelta.java:40`；前置校验也只校验 source（`AbstractDelta.java:58`）。
3. `restore` 一律只看 target position：`ChangeDelta.java:57`、`InsertDelta.java:49`、`DeleteDelta.java:49`。
4. 由 diff 自己产出的 delta，source 与 target 的 position 在插入/删除点上数值相同（INSERT 的 source size=0，`MyersDiff.java:169-170`；DELETE 的 target size=0，171-172 行），所以不能拿普通 diff 区分这两条不变量；只有手工构造或带位移的场景才能区分（新增测试第 2 条）。

### B. 遍历顺序

5. `applyToExisting` 要求 delta 按 source position **降序**落地：反向迭代器在 `Patch.java:72`，循环在 73-74 行；`restoreToExisting` 同样降序：262-265 行。
6. 升序保证由 `Patch.getDeltas()` 的就地排序负责：`Patch.java:284`（每次 getter 都排一次）。72 行和 262 行都是先经 `getDeltas()` 再取反向迭代器，因此“降序”依赖这一行。
7. 不存在显式 running offset：`applyTo`/`restore` 全链没有偏移变量；偏移完全由降序顺序隐式承担。delta 子类内部循环也只用相对 0 的下标（如 `ChangeDelta.java:45-52` 的 `position`/`position + i`）。

### C. `applyFuzzy` 的方向、账本与边界

8. `applyFuzzy` 正向遍历：`Patch.java:108` 的 for-each（经 `getDeltas()` 升序）。正向是必须的——它落地时会修改后续 delta 的实际位置，只能边改边记账；而 `applyTo` 用降序规避了记账。
9. 它额外记三本账（上下文对象 82-100 行 + 循环局部变量）：
   - `lastPatchDelta`：上一个 delta“实际落点 − 原 source position”的累计位移，声明 106 行，更新 113 行；
   - `defaultPosition`：当前 delta 的期望位置 = source position + `lastPatchDelta`，109 行（在 `PatchApplyingContext` 字段 92 行）；
   - `lastPatchEnd`：上一个 delta 落地后的尾位置（原 source 末位 + 位移），字段 87 行，更新 114 行，用于禁止后一个 delta 越过前一个；另外循环内还复用 `currentFuzz`（90 行）、`beforeOutRange/afterOutRange`（93-94 行）。
10. fuzz 分层：`findPositionFuzzy` 125 行外层循环 `fuzz = 0 .. maxFuzz`，**先 fuzz 后距离**——fuzz=0 的任何位置都比 fuzz=1 的近位置优先；126-129 行在当前 fuzz 找到即返回。
11. 同一 fuzz 内的距离扩张：`findPositionWithFuzz` 147 行 `moreDelta = 0,1,2,...`（`moreDelta++`，直到两个方向都越界 152-154 行）；`moreDelta=0` 先在 138-140 行试原位置。
12. 同距离两个方向同时成立时**往前（backward，下标更小）赢**：`findPositionWithFuzzAndMoreDelta` 先在 180-185 行验证 `defaultPosition - moreDelta`（before），186-191 行才验证 `defaultPosition + moreDelta`（after）；before 命中在 183 行直接 return，after 根本不会被检查。已实测对称候选时结果落在前面那个。
13. 搜索半径的上下界（161-193 行）：
    - 下界（不能早于上一个 patch 末尾）：`beginAt = defaultPosition - moreDelta + fuzz`（165 行），`beginAt <= lastPatchEnd` 时置 `beforeOutRange`（167-169 行）；
    - 上界（不能超出 result 尾部）：`beginAt = defaultPosition + moreDelta + source.size() - fuzz`（173 行），`result.size() < beginAt` 时置 `afterOutRange`（175-177 行）；
    - 两个方向都越界后由 147-155 行的循环终止；`for (moreDelta ... ; moreDelta >= 0; moreDelta++)` 的 `>=0` 只是整数溢出保护（145 行注释明说）。
14. fuzz 对 chunk 内容比较的裁剪：`Chunk.verifyChunk` 112-113 行 `startIndex=fuzz`、`lastIndex=size()-fuzz`，即忽略块两端各 fuzz 个元素，只比中间段（119-123 行）。
15. 匹配不上的冲突判定：fuzz 0..maxFuzz 全部找不到时 `findPositionFuzzy` 返回 -1（132 行），`applyFuzzy` 115-117 行以固定的 `VerifyChunk.CONTENT_DOES_NOT_MATCH_TARGET` 交 `conflictOutput` 处理；默认就是抛 `PatchFailedException`（198-201 行）。位置越界本身不产生 `POSITION_OUT_OF_TARGET`——fuzzy 路径只把“完全找不到”报成内容不匹配。
16. fuzzy 落地只有 `ChangeDelta` 支持：`AbstractDelta.applyFuzzyToAt` 默认实现直接抛 `UnsupportedOperationException`（`AbstractDelta.java:82-85`），只有 `ChangeDelta` 覆盖了它（`ChangeDelta.java:69-80`）；`EqualDelta` 覆盖为空操作（`EqualDelta.java:40-42`），`InsertDelta`/`DeleteDelta` 没有覆盖。

### D. unified diff 的行号、hunk 分组与无换行标记

17. hunk 头 1-based → 0-based 的转换点：
    - 老实现读取时在 `UnifiedDiffUtils.java:69-70` 把头里的数字解析成 1-based `old_ln/new_ln`，真正减 1 在构造 chunk 的 `UnifiedDiffUtils.java:127-128`（`old_ln - 1`、`new_ln - 1`）；行内 `-`/`+` 标记位置也在 115 行 `old_ln - 1 + removeNum`、122 行 `new_ln - 1 + addNum` 用 0-based 记录。
    - 新实现在 `UnifiedDiffReader.processChunk` 的 `UnifiedDiffReader.java:365-368` 读成 1-based（369-374 行再把 0 规范成 1），减 1 发生在 `finalizeChunk` 的 314、315、318、319、322、323、326、327 行（全部 `old_ln - 1` / `new_ln - 1`）；行内位置在 353 行和 360 行减 1。
    - 写出方向的 0-based → 1-based：老实现 `UnifiedDiffUtils.java:225`（`getPosition() + 1 - contextSize`，注释“+1 to overcome the 0-offset Position”）和 231 行；新实现 `UnifiedDiffWriter.java:141` 和 147 行。
18. 上下文行数参与 hunk 分组：分组条件是“前一个 delta 起点 + 其 source 长度 + contextSize” ≥ “下一个 delta 起点 − contextSize”则并入同一 hunk，否则断组——老实现在 `UnifiedDiffUtils.java:179-180`，新实现在 `UnifiedDiffWriter.java:94-95`；hunk 起点回退 contextSize 行并分别夹到 ≥1 / ≥0：老 225-240 行，新 141-156 行；hunk 尾部 context 同样按 contextSize 取并夹到文件尾：老 273-279 行，新 190-196 行。
19. 新读取器判断“一个 hunk 的数据收齐了”靠头里声明的 old_size/new_size 计数：`UnifiedDiffReader.java:182-186`，收齐即 `finalizeChunk()`（187 行）。老读取器没有计数概念，只在遇到下一个 `@@` 或输入结束时整体 flush（`UnifiedDiffUtils.java:67` 与 92 行），并且每个 hunk 无条件包成一个 `ChangeDelta`（126-128 行），即使 hunk 里只有纯删除或纯插入。
20. 新读取器在 `finalizeChunk` 里按内容分四类：有上下文或同时有删有增→`ChangeDelta`（`UnifiedDiffReader.java:312-315`），仅删→`DeleteDelta`（316-319 行），仅增→`InsertDelta`（320-323 行），都没有→`EqualDelta`（324-327 行）。这是算法之外 EQUAL delta 的第三个来源。
21. `\ No newline at end of file` 的两套待遇：
    - 老读取器：没有任何代码识别它。它以 `\` 开头，不属于空格/`+`/`-`，在 `UnifiedDiffUtils.java:82-84` 被静默忽略（既不进 chunk 也不报错）。老写出器 `generateUnifiedDiff` 全程不产出这一行。
    - 新读取器：`checkForNoNewLineAtTheEndOfTheFile`（`UnifiedDiffReader.java:214-220`）精确匹配该串，216 行在 `actualFile` 上置 `noNewLineAtTheEndOfTheFile=true` 标志（字段与 setter 在 `UnifiedDiffFile.java:45`、208-210 行），217 行直接读下一行，标记本身不进任何 chunk。
    - 新写出器：完全不消费这个标志——`UnifiedDiffWriter` 全文没有 `noNewLine` 相关输出（212-219 行的 `getDeltaText` 只写 `-`/`+`）。所以读进来再写回去，这行必丢（已实测）。仓库里唯一针对该行为的测试 `UnifiedDiffRoundTripNewLineTest` 整个类被 `@Disabled("for next release")` 挂起（该文件 28 行）。

### E. `DiffRowGenerator` 行内切分与标签闭合

22. 行内差异用的 equalizer 是 generator 自己那份，不是算法工厂那份：字段 `DiffRowGenerator.java:182`，构造时若 builder 显式给了就用给的（209-210 行），否则按 `ignoreWhiteSpaces` 选 `IGNORE_WHITESPACE_EQUALIZER`（57-58 行定义）或 `DEFAULT_EQUALIZER`（55 行），选择在 212 行。它只被两处使用：行级 diff `generateDiffRows(original, revised)` 的 237 行 `DiffUtils.diff(original, revised, equalizer)`，和行内 diff 的 404 行 `DiffUtils.diff(origList, revList, equalizer)`。注意这两处走的是 `DiffUtils.java:114-120` 那个重载（见第一部分的 null 坑，但这里永远非空）。
23. 切词规则：默认按字符切 `SPLITTER_BY_CHARACTER`（`DiffRowGenerator.java:65-71`，builder 默认 535 行）；按词切的 pattern 是 `SPLIT_BY_WORD_PATTERN = \s+|[,.\[\](){}/\\*+\-#<>;:&\']+`（73 行），即空白串或一串标点为分隔符。`SPLITTER_BY_WORD`（78-79 行）调 `splitStringPreserveDelimiter`（101-118 行）：用 matcher 扫，普通片段（107-109 行）和分隔符片段（110 行）**都**加进列表，所以重新拼接能无损还原；末尾残余在 113-115 行补入。也可用 `inlineDiffBySplitter` 自定义（723-726 行）。
24. 行内 delta 的处理顺序：`generateInlineDiffs` 先把多行用 `\n` 拼起来（397-398 行）、切分（400-401 行）、算字符/词级 diff（403-404 行）、可选 merge（405-406 行），然后在 408 行 `Collections.reverse(inlineDeltas)`，409 行起倒序 `wrapInTag`。**必须倒序**：标签是用 `List.add(index, ...)` 插进列表的，先处理靠后的 delta 才不会让靠前 delta 的位置失效（见第四部分）。
25. 成对闭合靠 `wrapInTag`（128-179 行）的固定动作顺序：先在 155 行 `sequence.add(endPos, 闭标签)`，再在 176 行 `sequence.add(endPos, 开标签)`——因为 155 行插入闭标签后元素整体后移一位，176 行仍用收缩后的 `endPos` 插入开标签，正好落在被包内容之前；每个 delta 调用恰好产生一对标签（155 与 176 成对出现）。遇到 `\n` 片段时按 `replaceLinefeedWithSpace` 收缩边界（141-149、162-169 行），若整个区间只剩换行则 151-153 行直接 break（此时该区间一个标签也不输出，而不是输出单边标签），因此不会出现只有开没有闭的情况。
26. mergeOriginalRevised 模式下 INSERT/CHANGE 会先把 revised 片段 `addAll` 进 origList（423-425、446-448 行）再包标签；插入之后再 `wrapInTag` 的起止位置按插入后的长度算（429、451-452 行），顺序同样不能换。
27. 最外层行序也依赖 delta 升序：`generateDiffRows` 用 `endPos` 游标在 257/262 行处理 delta、`transformDeltaIntoDiffRow` 在 282 行取 `original.subList(endPos, orig.getPosition())` 补 EQUAL 行，并在 311 行返回 `orig.last() + 1`。delta 乱序会直接抛 `IndexOutOfBoundsException`（subList 的 from>to）。

### F. 测试执行上的事实

28. 根 `pom.xml:183-185` 给 surefire 配了 `<exclude>**/LR*.java</exclude>`，`java-diff-utils/pom.xml:57-63` 自己的 surefire 配置没有覆盖它。唯一命中的类是 `java-diff-utils-jgit/src/test/java/com/github/difflib/algorithm/jgit/LRHistogramDiffTest.java`——它在 reactor 全量 `mvn test` 里**从来没被跑过**。这条规则本次按要求保留不动，但意味着 jgit `HistogramDiff` 的行为实际没有任何 CI 断言兜底（注册为默认算法后会怎样，见第一部分第 2 节与风险三的旁证）。

---

## 三、三个真正有风险的地方（三个不同文件）

### 风险一：`Chunk.verifyChunk` 越界检查写错符号，位置越界时抛裸 `IndexOutOfBoundsException` 而不是返回冲突状态

- 文件：`java-diff-utils/src/main/java/com/github/difflib/patch/Chunk.java:116`
- 代码：`if (position + fuzz > target.size() || last - fuzz > target.size())`，其中 `last = position + size() - 1`（114 行）。正确的右界比较应当对“最后一个要访问的下标 +1”与 size 比，即应为 `last - fuzz + 1 > target.size()`（或 `>=`）；现在少了 `+1`。
- 触发形状：chunk 起点落在 `target.size() - size()` 与 `target.size() - 1` 之间，且被比较的前几个元素恰好都相等，直到循环读到越界下标。具体最小例：target 长 5（下标 0..4），chunk = position 4、lines `[X, Y]`，target[4] 等于 X。循环 119-123 行会先成功比较 `target.get(4)`，随后 `target.get(5)` 直接 AIOOBE；而按 116 行的意图这应返回 `POSITION_OUT_OF_TARGET`。已实测：`IndexOutOfBoundsException: Index 5 out of bounds for length 5`。
- 复现步骤：
  1. `new Chunk<>(4, Arrays.asList("X", "Y")).verifyChunk(Arrays.asList("a","b","c","d","X"))`；
  2. 观察抛出未声明的运行时异常，而不是 `VerifyChunk.POSITION_OUT_OF_TARGET`。
  通过公开 API 复现：构造 source position 越过目标尾的 `DeleteDelta`/`ChangeDelta` 装进 `Patch`，调 `patch.applyTo(target)` 且不换 `conflictOutput`——异常发生在 `AbstractDelta.verifyAndApplyTo`（`AbstractDelta.java:62`）→ 120 行，连 `Patch.java:76-78` 的冲突处理都到不了，用户拿不到 `PatchFailedException` 也拿不到 merge conflict。
- 现有测试为什么没盖住：全仓没有任何测试引用 `POSITION_OUT_OF_TARGET`（`rg POSITION_OUT_OF_TARGET src/test` 无命中）；`ChunkTest` 只覆盖内容匹配/不匹配两种正常情况。现有 apply 测试的 delta 全部来自真实 diff，真实 diff 的位置天然不越界。

### 风险二：默认算法产出的纯 INSERT/DELETE delta 调 `applyFuzzy` 必抛 `UnsupportedOperationException`

- 文件：`java-diff-utils/src/main/java/com/github/difflib/patch/AbstractDelta.java:82-85`
- 根因：fuzzy 落地方法在基类默认直接 `throw new UnsupportedOperationException(... does not supports applying patch fuzzy)`；只有 `ChangeDelta` 覆盖（`ChangeDelta.java:69-80`），`InsertDelta`、`DeleteDelta` 均未覆盖（`InsertDelta.java` 全文无该方法，`DeleteDelta.java` 同）。而 fuzzy 的文档（79 行链接 GNU inexact patch）给人的预期是所有 hunk 都能模糊应用。
- 触发形状：任何包含纯插入或纯删除的 patch 调 `applyFuzzy`。默认经典 `MyersDiff` 对纯插入/纯删除就归类成 `InsertDelta`/`DeleteDelta`（`MyersDiff.java:169-172`）；linear-space 实现更糟，连“两边同时改”都拆成 INSERT+DELETE（`MyersDiffWithLinearSpace.java:94`、105 行），所以它产出的 patch 几乎一 fuzzy 就炸。已实测：`DiffUtils.diff(["hhh"], ["hhh","jjj","kkk","lll"]).applyFuzzy(..., 0)` → `UnsupportedOperationException: InsertDelta does not supports applying patch fuzzy`；linear-space 的 replace 场景同样实测 UOE。
- 复现步骤：
  1. `Patch<String> p = DiffUtils.diff(Arrays.asList("hhh"), Arrays.asList("hhh","jjj","kkk","lll"));`
  2. `p.applyFuzzy(new ArrayList<>(Arrays.asList("hhh")), 0);` → UOE，即使目标内容与原文件完全一致、fuzz=0 本应是平凡成功。
- 现有测试为什么没盖住：唯一的 fuzzy 测试 `WithMyersDiffWithLinearSpacePatchTest.fuzzyApply`（92-126 行）等四个用例全部是**手工 new 的 `ChangeDelta`**（97、133-134、144-145 行），没有一个 fuzzy 用例从 `DiffUtils.diff(...)` 的结果出发；而该文件里真正走算法的用例（25-65 行）只测 `applyTo`，不测 `applyFuzzy`。

### 风险三：`UnifiedDiffWriter` 读-写往返丢失 `\ No newline at end of file`，并把缺失标记的文件静默当成有换行

- 文件：`java-diff-utils/src/main/java/com/github/difflib/unifieddiff/UnifiedDiffWriter.java`（整类，关键在 212-219 行 `getDeltaText` 与 54-121 行 `write` 主流程）
- 根因：读取侧已经把标志解析进了模型（`UnifiedDiffReader.java:214-220` 置 `UnifiedDiffFile.noNewLineAtTheEndOfTheFile`，`UnifiedDiffFile.java:45`、208-210 行），但写出侧从头到尾不读这个字段，`getDeltaText`（212-219 行）对每个源/目标行机械地输出 `-`/`+`，没有任何路径输出 `\ No newline at end of file`。已实测：读入带该标记的 hunk 后 `isNoNewLineAtTheEndOfTheFile()` 为 true，但 `UnifiedDiffWriter.write(...)` 的输出里该行消失。下游若再拿写回的文本去 apply，对“文件末尾无换行”的判定（issue #135 场景）就会错。
- 触发形状：任何“最后一行没有结尾换行”的 unified diff 做读 → 写（或读 → apply 后比对文本）。注意写出时每行都无条件补 `\n`（`UnifiedDiffWriter.java:44-48`），所以连“最后一行末尾不加换行”这层信息也一起丢。
- 复现步骤：
  1. 构造包含 `"\\ No newline at end of file\n"` 的 unified diff 字节流；
  2. `UnifiedDiffReader.parseUnifiedDiff(...)` 读入，断言 `file.isNoNewLineAtTheEndOfTheFile()` 为 true；
  3. `UnifiedDiffWriter.write(diff, name -> originalLines, writer, 0)`；
  4. 观察输出不含该标记行（实测确认）。
- 现有测试为什么没盖住：唯一对应测试 `java-diff-utils/src/test/java/com/github/difflib/unifieddiff/UnifiedDiffRoundTripNewLineTest.java:28` 整个类标注 `@Disabled("for next release")`，surefire 会跳过；`UnifiedDiffRoundTripTest`/`UnifiedDiffWriterTest` 的素材文件都以正常换行结尾，不经过 216 行这条分支。

旁证（同一主题但落在另一个文件）：老的 `UnifiedDiffUtils.parseUnifiedDiff` 对该行更差——`\` 前缀在 `UnifiedDiffUtils.java:82` 不匹配任何分支，被静默丢弃，既不报错也无标志位；所以两套实现对同一输入的语义都不完整，只是失败方式不同。

---

## 四、相邻两步先后不能对调的地方（对调后编译通过、测试可能仍绿，但输出错误）

### 对调点 1（把 delta 打到目标文本的路上）：`applyToExisting` 必须“先校验后落地”，且整体必须降序

文件：`java-diff-utils/src/main/java/com/github/difflib/patch/Patch.java:71-80` 与 `java-diff-utils/src/main/java/com/github/difflib/patch/AbstractDelta.java:61-67`。

这里其实有两个紧挨着、都不能换的次序：

**(a) verify 与 apply 的次序**（`AbstractDelta.java:62-65`）：现在是 62 行先 `verifyChunkToFitTarget`，63 行判断 OK 后 64 行才 `applyTo`。对调成“先 apply 再 verify”会先失效在具体子类的 `remove` 上——`ChangeDelta.applyTo` 的 45-47 行、`DeleteDelta.applyTo` 的 42-44 行都是在固定 position 上无条件 `target.remove(position)` 共 source.size() 次。当位置不对（越界或内容不符）时，list 会在 verify 之前就被删掉若干元素甚至直接 `IndexOutOfBoundsException`；即使侥幸没越界，随后的 verify 也是在“已被自己改过”的 list 上比对，冲突状态（`Patch.java:76-78` 依赖的返回值）要么失真要么永远走不到。错出来的结果：目标 list 被删残，且 `CONFLICT_PRODUCES_MERGE_CONFLICT` 这类冲突处理再也拿不到原始现场。

**(b) 降序与升序的次序**（`Patch.java:72-74`）：72 行从 `deltas.size()` 取反向迭代器是“不累计偏移”能成立的全部前提。看似等价的改写是把 72 行换成 `for (delta : getDeltas())` 正向跑。对纯插入有时结果不变（插入只推后面的元素，而后面 delta 还没处理——但第二个插入点之后的行会被推两次，顺序敏感场景会错），对删除/替换则必错。具体实测复现：patch 含两个 `DeleteDelta`，source position 分别 0（删 "a"）和 2（删 "c"），目标 `[a,b,c]`。

- 降序（现状）：先删位置 2 → `[a,b]`，再删位置 0 → `[b]`，正确；
- 升序（对调后）：先删位置 0 → `[b,c]`，第二个 delta 在 length 2 的 list 上 verify 位置 2，命中风险一那条越界路径，`Chunk.java:120` 抛 `IndexOutOfBoundsException: Index 2 out of bounds for length 2`，apply 中途死亡，连 conflictOutput 都进不去。

即便避开越界边界（把第二个 delta 换成在 1 附近的 change），升序也会在错误的行上比对/替换，产出静默错行的文本而不是异常——这正是“编译过、测试可能还绿、结果错”的形状。

### 对调点 2（把差异渲染成带标签行的路上）：行内 delta 必须先 `reverse` 再依次包标签

文件：`java-diff-utils/src/main/java/com/github/difflib/text/DiffRowGenerator.java:408-409` 与 128-179 行。

`generateInlineDiffs` 在 403-404 行算出的 `originalInlineDeltas` 是按位置升序的；现状在 408 行 `Collections.reverse(inlineDeltas)` 之后，409 行才开始 for 循环调 `wrapInTag`。对调成“先包标签再 reverse”（或直接删掉 reverse 升序包）会先失效在 `wrapInTag` 的插入语义上：155 行和 176 行是 `sequence.add(position, tag)`——往列表中间插入会让插入点之后的所有元素下标 +1。包第一个靠前的 delta 后，后面 delta 记录的 `getPosition()`（415-416、437-438、469-470 行用的都是 diff 时算好的旧位置）已经不再指向它原本的片段，于是第二个 delta 的标签会插到错误的 token 上。错出来的结果长这样：HTML 标签错位，例如原文 `aBa→aba` 这种两个改动点，可能渲染成开标签落在第一个 token 前、闭标签落到中间未改动的字符后，得到 `<span...>aB</span>a` 与 `a<span...>b</span>a` 标签位置整体平移，严重时开闭标签跨 `\n` 交错（486-487 行再按 `\n` split 时会把半边标签甩到相邻行），破坏 25 号不变量承诺的成对闭合。

为什么现有测试可能照样绿：单 delta 的行内 diff（`DiffRowGeneratorTest` 里大量单行替换用例）reverse 与否结果相同，因为列表里只有一个元素；只有一个 CHANGE 块内出现**两个及以上**行内 delta（例如同一行有两处不相邻的改动）时错位才可见，而这类用例在测试套件里很少且多配合 merger 使用，merge 后常常又只剩一个 delta。

---

## 五、新增测试如何钉住不变量

文件：`java-diff-utils/src/test/java/com/github/difflib/patch/ApplyOrderInvariantTest.java`（JUnit 5 + AssertJ，无新依赖）。

- `applyToRequiresDescendingDeltaOrderSoPositionsStayOriginalCoordinates`：钉 5/6/7 号不变量。手工放两个 source position 为 0、2 的 `DeleteDelta`，断言 `getDeltas()` 升序为 [0,2]（钉 `Patch.java:284`），再断言 `applyTo([a,b,c])` 内容恰为 `[b]`。若有人把 `Patch.java:72` 的反向迭代器改成正向 for，该用例不是“没抛异常就算了”——它会以 `IndexOutOfBoundsException` 挂掉（反射实证见第四部分 (b)），即使换成不越界的 delta 形状内容断言也会对不上。
- `applyToUsesSourcePositionAndRestoreUsesTargetPosition`：钉 1-4 号不变量。构造 source position=1、target position=4 且两侧长度不同的 `ChangeDelta`，分别断言 apply 后与 restore 后的完整列表内容。把 `ChangeDelta.java:43` 的 `getSource()` 换成 `getTarget()`、或 57 行反过来，下标和插回行数立刻错位，断言红。
- `fuzzySearchPrefersBackwardCandidateOnSymmetricTie`：钉 12 号不变量。default position 两侧等距离放两个全等候选，断言落点是前面的候选。对调 `Patch.java:180-191` 的 before/after 两段，结果会落到后一个候选，内容断言红。
- `fuzzySearchWithoutAnyCandidateReportsContentConflict`：钉 15 号不变量。任何 fuzz 都匹配不上时断言抛出的 `PatchFailedException` 消息包含 `CONTENT_DOES_NOT_MATCH_TARGET`，防止有人把找不到的情况静默吞掉或误改成 `POSITION_OUT_OF_TARGET`。

验证方式：`mvn -B -Dspotless.apply.skip=true test`（根目录，全 reactor）。

