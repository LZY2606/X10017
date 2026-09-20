# java-diff-utils 调用链与不变量分析

分析基于当前工作区源码（模块 `java-diff-utils` 为主体，`java-diff-utils-jgit` 只读参考）。
所有行号对应当前提交的文件，格式为 `相对路径:行`。

## 一、从 `DiffUtils.diff` 到 `Patch.applyTo` 的完整调用链

### 1.1 入口重载与默认算法

`DiffUtils` 中的 `diff` 重载共 7 个，最终只有一个"干活"的方法：

- `DiffUtils.diff(List, List, DiffAlgorithmListener)`：`java-diff-utils/src/main/java/com/github/difflib/DiffUtils.java:60`，转调 `diff(original, revised, DEFAULT_DIFF.create(), progress)`（`DiffUtils.java:62`）。
- `DiffUtils.diff(List, List)`：`DiffUtils.java:73` → `DEFAULT_DIFF.create()`（`DiffUtils.java:74`）。
- `DiffUtils.diff(List, List, boolean includeEqualParts)`：`DiffUtils.java:86` → `DEFAULT_DIFF.create()`（`DiffUtils.java:87`）。
- `DiffUtils.diff(String, String, DiffAlgorithmListener)`：`DiffUtils.java:98`，先按 `\n` 切行（`DiffUtils.java:99`）再回到第一个重载。
- `DiffUtils.diff(List, List, BiPredicate equalizer)`：`DiffUtils.java:114`，**这是唯一特殊的重载**，见 1.3。
- `DiffUtils.diff(List, List, DiffAlgorithmI)`：`DiffUtils.java:165` → 带 `progress` 的重载（`DiffUtils.java:167`）→ `DiffUtils.java:122`。
- 真正干活的终点：`DiffUtils.diff(original, revised, algorithm, progress, includeEqualParts)`，`DiffUtils.java:142`；三处 `Objects.requireNonNull` 在 `DiffUtils.java:148-150`；最后一行（`DiffUtils.java:152`）调用
  `Patch.generate(original, revised, algorithm.computeDiff(original, revised, progress), includeEqualParts)`。

默认算法的存放与修改：

- 默认值是包可见静态字段 `DEFAULT_DIFF`，初值 `MyersDiff.factory()`：`DiffUtils.java:40`。
- 唯一修改入口 `DiffUtils.withDefaultDiffAlgorithmFactory(...)`：`DiffUtils.java:47-49`，进程级全局状态，直接替换静态字段，无同步、无恢复。
- 是否所有重载都按它走：**基本是，但有一个例外**。
  - 不传算法实例、不传 equalizer 的 4 个重载都走 `DEFAULT_DIFF.create()`（`DiffUtils.java:62, 74, 87`；String 版经 `:99` 间接走）。
  - 显式传 `DiffAlgorithmI` 的两个重载当然用调用方给的算法（`DiffUtils.java:152`）。
  - 例外是 equalizer 重载的 `equalizer == null` 分支：它不经过 `DEFAULT_DIFF`，而是 `new MyersDiff<>()`（`DiffUtils.java:119`），见 1.3。

### 1.2 equalizer 的逐层传递与最终落点

"equalizer"指 `BiPredicate<? super T, ? super T>`，是元素相等性判定。

- 算法侧持有：`MyersDiff.equalizer` 为 `final` 字段（`algorithm/myers/MyersDiff.java:34`）；无参构造默认 `Object::equals`（`MyersDiff.java:36-38`）；带参构造要求非空并保存（`MyersDiff.java:40-43`）。
- 工厂侧透传：`MyersDiff.factory()`（`MyersDiff.java:187-199`）的 `create()` 返回 `new MyersDiff<>()`（`MyersDiff.java:191`），`create(equalizer)` 返回 `new MyersDiff<>(equalizer)`（`MyersDiff.java:196`）。`DiffAlgorithmFactory` 接口定义在 `algorithm/DiffAlgorithmFactory.java:25-29`。
- `DiffUtils` 侧：equalizer 只出现在 `DiffUtils.java:114-120` 这一个重载里。非空时交给 `DEFAULT_DIFF.create(equalizer)`（`DiffUtils.java:117`）；为空时 `new MyersDiff<>()`（`DiffUtils.java:119`）。
- 最终被调用的位置（Myers 标准版）：贪心扩展蛇形段的内层循环 `equalizer.test(orig.get(i), rev.get(j))`，`MyersDiff.java:117`。
- 线性空间版 `MyersDiffWithLinearSpace` 结构相同：字段 `:35`、默认构造 `:37-39`、带参构造 `:41-44`，equalizer 被调用在三处：`algorithm/myers/MyersDiffWithLinearSpace.java:83`（分治基线循环）、`:149`（向下搜索蛇形）、`:173`（向上搜索）、`:192`（`buildSnake`）。
- 渲染侧 `DiffRowGenerator` 自己还持有一个 equalizer（`text/DiffRowGenerator.java:182`），来源是 Builder：显式设置优先，否则按 `ignoreWhiteSpaces` 选 `IGNORE_WHITESPACE_EQUALIZER` 或 `DEFAULT_EQUALIZER`（`DiffRowGenerator.java:209-213`）；它在行级 diff（`DiffRowGenerator.java:237`）和行内字符/词级 diff（`DiffRowGenerator.java:404`）两处被传进 `DiffUtils.diff(..., equalizer)`，最终同样落到上面的 `equalizer.test`。

**"传它"和"不传它"两条路是否等价：分两层看。**

1. 只比较标准 Myers 默认状态时：等价。`DEFAULT_DIFF.create()` → `new MyersDiff<>()` → equalizer 就是 `Object::equals`（`MyersDiff.java:37`）；equalizer 传 `null` 时 `new MyersDiff<>()`（`DiffUtils.java:119`），同一个类、同一个默认谓词；而显式传 `Object::equals` 只是把同一个方法引用显式喂给构造器（`MyersDiff.java:40-43`）。三者逐元素判定行为一致。
2. 一旦有人调用过 `DiffUtils.withDefaultDiffAlgorithmFactory(...)`：**不再等价**。
   - 显式传非空 equalizer 走 `DEFAULT_DIFF.create(equalizer)`（`DiffUtils.java:117`），会使用被替换后的工厂（例如线性空间 Myers 或 jgit `HistogramDiff`）。
   - 传 `null` 却硬编码 `new MyersDiff<>()`（`DiffUtils.java:119`），全局替换对它完全失效。
   - 实测：把全局工厂换成 `MyersDiffWithLinearSpace.factory()` 后，对同一份"替换一行"的输入，传 `String::equals` 得到 `[INSERT, DELETE]` 两个 delta（线性空间算法不产 CHANGE，见 1.4），传 `null` 仍得到单个 `[CHANGE]`。这也是第三节风险 R2。

另外注意 `DiffRowGenerator` 的默认值文档注释（`DiffRowGenerator.java:44-45` 写着 ignoreWhiteSpaces 默认 true）与 Builder 实际默认值 `false`（`DiffRowGenerator.java:526`）不一致，但构造器选择逻辑（`:209-213`）以字段为准，equalizer 传递链本身不受影响。

### 1.3 算法执行与 `Change` 的产出

- `DiffAlgorithmI.computeDiff` 接口：`algorithm/DiffAlgorithmI.java:37`。
- 标准 Myers：`MyersDiff.computeDiff`（`MyersDiff.java:51`）先 `buildPath`（`:59`，实现 `:77-136`，equalizer 调用在 `:117`），再 `buildRevision`（`:60`，实现 `:148-182`）。
- **INSERT / DELETE / CHANGE 的归类就发生在 `MyersDiff.buildRevision` 的 `MyersDiff.java:169-175`**：沿路径从后往前，比较非蛇形节点 `(i,j)` 与前一锚点 `(ianchor,janchor)`：
  - `ianchor == i && janchor != j` → `new Change(DeltaType.INSERT, ianchor, i, janchor, j)`（`MyersDiff.java:169-170`）；
  - `ianchor != i && janchor == j` → `DELETE`（`MyersDiff.java:171-172`）；
  - 其余（i、j 都变）→ `CHANGE`（`MyersDiff.java:173-174`）。
  - `Change` 是四个 `public final` 坐标字段的纯数据载体：`algorithm/Change.java:24-38`（`startOriginal/endOriginal/startRevised/endRevised`，半开区间）。
- 线性空间版归类不同：分治收敛后在 `MyersDiffWithLinearSpace.buildScript` 里逐元素发命令，只会追加 `DELETE`（`:94`）或 `INSERT`（`:105`），相邻同类命令用 `withEndOriginal/withEndRevised`（`Change.java:40-46`，调用点 `MyersDiffWithLinearSpace.java:96-98, 107-109`）合并；**它从不产生 CHANGE**，替换在 `Patch` 层面表现为紧邻的 DELETE+INSERT。
- jgit `HistogramDiff`：把 jgit 的 `Edit.Type` 映射成 `DELETE/INSERT/CHANGE`（`java-diff-utils-jgit/src/main/java/com/github/difflib/algorithm/jgit/HistogramDiff.java:50-63`），能产 CHANGE；但它的比较器 `DataListComparator.equals` 写死 `original.data.get(orgIdx).equals(...)`（`HistogramDiff.java:85`），没有 equalizer 字段/构造参数，工厂也没实现——即第三方算法无法享受自定义 equalizer。

### 1.4 `Change` → `AbstractDelta` → `Patch`

转换全部在 `Patch.generate(original, revised, changes, includeEquals)`：`patch/Patch.java:301-348`。

- `includeEquals=false`（默认，`Patch.java:293-295`）：直接遍历算法给的 `changes`（`Patch.java:314`）。
- `includeEquals=true`：先按 `startOriginal` 排序复制一份（`Patch.java:309-312`），因为算法给的 CHANGE 顺序不保证（Myers 是倒着构造的）。
- 每个 change 用 `buildChunk`（`Patch.java:297-299`，`subList(start,end)` 是半开区间）构造原文 chunk（`Patch.java:322`）和改后 chunk（`Patch.java:323`），再在 `switch`（`Patch.java:324-335`）里落成 `DeleteDelta`（`:326`）、`InsertDelta`（`:329`）、`ChangeDelta`（`:332`）。
- **EQUAL 不是算法造的**：算法层从不产 EQUAL。`EqualDelta` 只在 `includeEquals=true` 时由 `Patch.generate` 造：
  - 两个 change 之间的相等段：`Patch.java:316-320`；
  - 最后一个 change 到文件尾的相等段：`Patch.java:341-345`。
  - 所以只有显式走 `DiffUtils.diff(original, revised, true)`（`DiffUtils.java:86`）并最终到 `DiffUtils.java:152` 传 `includeEqualParts=true` 时，patch 里才会出现 EQUAL。另一个造 EQUAL 的地方是新 unified diff 读取器：`unifieddiff/UnifiedDiffReader.java:325-327`（一个 hunk 全是上下文行时），与算法无关。
- 四个 delta 子类都在构造时固定 `DeltaType`（`ChangeDelta.java:36`、`InsertDelta.java:35`、`DeleteDelta.java:35`、`EqualDelta.java:27`），两个 chunk 存于 `AbstractDelta` 的 `final` 字段（`patch/AbstractDelta.java:27-28, 36-37`）。

### 1.5 应用链：`Patch.applyTo`

- `Patch.applyTo(target)`：`Patch.java:57-61`，先拷贝 `new ArrayList<>(target)`（`:58`），再 `applyToExisting(result)`（`:59`）。
- `Patch.applyToExisting`：`Patch.java:71-80`。关键点：
  - 取迭代器时定位到末尾 `getDeltas().listIterator(deltas.size())`（`Patch.java:72`），随后 `hasPrevious/previous` **从后往前**遍历（`Patch.java:73-74`）。
  - 每个 delta 调 `delta.verifyAndApplyTo(target)`（`Patch.java:75`）。
  - 返回值不是 `OK` 才交给冲突处理器 `conflictOutput.processConflict(...)`（`Patch.java:76-78`）。
- `AbstractDelta.verifyAndApplyTo`：`AbstractDelta.java:61-67`，先 `verifyChunkToFitTarget`（`:62`，其实现 `:57-59` 调 `getSource().verifyChunk(target)`），**仅当 `verify == OK` 才 `applyTo(target)`**（`:63-65`）。
- `Chunk.verifyChunk`：`patch/Chunk.java:97-125`。无参版 `:97-99` 用 `fuzz=0, position=getPosition()`；带参版 `:110-125` 做边界判定（`:116-118`）后逐元素比较（`:119-123`），全等返回 `OK`（`:124`），不一致返回 `CONTENT_DOES_NOT_MATCH_TARGET`（`:121`），越界返回 `POSITION_OUT_OF_TARGET`（`:117`）。
- 具体落地（都只动传入的可变 list）：
  - `ChangeDelta.applyTo`：`patch/ChangeDelta.java:42-53`——先在原 position 连续 `remove` source.size() 次（`:45-47`），再把 target 行 `add(position+i, ...)`（`:48-52`）。
  - `DeleteDelta.applyTo`：`patch/DeleteDelta.java:39-45`。
  - `InsertDelta.applyTo`：`patch/InsertDelta.java:39-45`。
  - `EqualDelta.applyTo`：空操作，`patch/EqualDelta.java:31`。
- 冲突处理默认抛异常 `CONFLICT_PRODUCES_EXCEPTION`（`Patch.java:198-201`，字段 `:229`），可 `withConflictOutput` 换成 git 风格标记（`Patch.java:206-227, 235-238`）。

**多个 delta 之间到底有没有累计位置偏移？`applyTo` 路径没有显式累计变量，但偏移被"倒序遍历"隐式消掉了。**

- 所有 delta 的 position 在 `Patch.generate` 时就固化为原始坐标（`Patch.java:322-323`），应用过程中从不重算。
- 因为从后往前打（`Patch.java:72-74`），对位置 `p` 的增删只影响 `> p` 的索引，而那些 delta 已经处理过；排在前面的 delta 用到的 `position` 仍然有效。
- 实测：两个 insert（原坐标 1 插两行、原坐标 4 插一行），倒序打得到正确的 `[a, B1, B2, b, c, d, D1, e]`；若把 `Patch.java:72-74` 换成正向迭代，第二个 insert 会在已经膨胀后的 list 里插到错误下标，得到 `[a, B1, B2, b, D1, c, d, e]`（`D1` 跑到了 `c` 前）。这条已被新增测试 `applyToMustProcessDeltasInReversePositionOrder` 钉死。
- 对照：fuzzy 路径是正向遍历，所以它**必须显式记账**（见不变量 I-FUZZY）。

### 1.6 反向链：`Patch.restore`

- `Patch.restore(target)`：`Patch.java:247-251`（拷贝后 `restoreToExisting`，`:261-267`）。
- 同样倒序遍历（`Patch.java:262-264`），但直接调 `delta.restore(target)`（`:265`），**不做 verify**。
- 各 delta 的 restore 用的是 **target chunk** 的坐标（与 applyTo 相反）：
  - `ChangeDelta.restore`：`ChangeDelta.java:56-67`，`position = getTarget().getPosition()`（`:57`）；
  - `InsertDelta.restore`：在 target 坐标删除，`InsertDelta.java:48-54`；
  - `DeleteDelta.restore`：在 target 坐标插回，`DeleteDelta.java:48-54`；
  - `EqualDelta.restore`：空操作，`EqualDelta.java:34`。
- 便捷入口 `DiffUtils.patch`/`unpatch`：`DiffUtils.java:205-219`。

## 二、不变量清单（每条落到具体行）

### I1. 两个 chunk 的坐标归属与 apply/restore 的选择

- `AbstractDelta` 持有 `source` 与 `target` 两个 `final Chunk`：`AbstractDelta.java:27-28`。
- `source`（`getSource()`，`AbstractDelta.java:40-42`）的 `position` 永远是**原文/original 侧坐标**；`target`（`getTarget()`，`:44-46`）的 `position` 永远是**改后/revised 侧坐标**。坐标在 `Patch.generate` 构造 chunk 时就定死（`Patch.java:322-323`，数据来自 `Change.startOriginal/startRevised`，`Change.java:27-30`）。
- **applyTo 只用 source 坐标**：验证 `AbstractDelta.java:58` → `Chunk.verifyChunk`（`Chunk.java:97-99`）；改动 `ChangeDelta.java:43`、`DeleteDelta.java:40`、`InsertDelta.java:40` 全部读 `getSource().getPosition()`。
- **restore 只用 target 坐标**：`ChangeDelta.java:57`、`InsertDelta.java:49`、`DeleteDelta.java:49` 全部读 `getTarget().getPosition()`。
- chunk 行数语义：`Chunk.size() = lines.size()`（`Chunk.java:152-154`），`last() = position + size() - 1`（`Chunk.java:159-161`）；INSERT 的 source、DELETE 的 target 是空 list（size 0）。

### I2. applyTo 的遍历顺序要求与保证者

- 要求：必须按 source.position **从大到小**应用。保证者不是调用方，而是 `Patch` 自己：
  - delta 次序在每次 `getDeltas()` 时被按 source.position 升序排序：`Patch.java:283-286`（`deltas.sort(comparing(d -> d.getSource().getPosition()))`，`:284`）。
  - `applyToExisting` 从列表尾取迭代器倒着走：`Patch.java:72-74`。
  - `restoreToExisting` 同样倒序：`Patch.java:262-264`。
- 因此"先验证后改动"（`AbstractDelta.java:62-65`）使用的 position 在轮到该 delta 时一定仍然指向未受后续 delta 影响的位置。
- 注意 `getDeltas()` 有副作用（每次调用都 `sort`，`Patch.java:284`）；`applyFuzzy` 的正向循环（`Patch.java:108`）也依赖这次排序。

### I3. fuzzy 的遍历方向、额外的账与搜索边界

- 方向：`applyFuzzy` **正向**遍历（`Patch.java:108`，`for (AbstractDelta<T> delta : getDeltas())`），与 applyTo 相反。
- 为什么必须正向：fuzzy 是"边找边改"，前一个补丁落地后会改变后续补丁的搜索空间；它需要顺着结果生长/收缩的方向推进，并用账本把原始 patch 坐标换算成当前 list 坐标。倒序则无法用统一的累计量修正"还没处理的"前缀。
- 额外的账（`PatchApplyingContext`，`Patch.java:82-100`，外加方法内局部变量）：
  1. `lastPatchDelta`（`Patch.java:106`，更新于 `:113`）：上一个补丁实际落点与它 patch 坐标的差；用于计算本次默认位置 `defaultPosition = source.position + lastPatchDelta`（`Patch.java:109`）。
  2. `ctx.lastPatchEnd`（字段 `:87`，初值 -1，更新于 `:114` = `source.last() + lastPatchDelta`）：上一个补丁在当前结果里的末端，禁止本次落点越到它之前（`Patch.java:164-170`）。
  3. `ctx.currentFuzz`（`:90`）：当前实际命中所用的 fuzz 层数，落地时传给 `applyFuzzyToAt`（`:112`）。
  4. `ctx.defaultPosition`（`:92`）、`beforeOutRange`/`afterOutRange`（`:93-94`）：搜索中心和两个方向是否已撞到边界。
- fuzz 层数：`findPositionFuzzy` 从 0 递增到 `maxFuzz`，第一层命中即返回（`Patch.java:124-133`）；每层先试默认位置（`Patch.java:138-140`），再对称扩张 `moreDelta=0,1,2,...`（`Patch.java:147-155`）。
- **向前/向后各能搜多远（上下界）**：
  - 下界（不能跑到上一个补丁之前）：`beginAt = defaultPosition - moreDelta + fuzz <= lastPatchEnd` 时置 `beforeOutRange`（`Patch.java:164-170`）。注意比较用 `<=`，即紧贴上一个补丁末端也不允许。
  - 上界（不能超出结果尾）：`defaultPosition + moreDelta + source.size() - fuzz > result.size()` 时置 `afterOutRange`（`Patch.java:172-178`）。
  - 两个方向都越界才停止扩张（`Patch.java:152-154`）；`moreDelta` 用 `>= 0` 的自增循环兼作溢出保护（`Patch.java:145-147`）。
- **同半径两个候选同时成立时谁赢：后向（更靠前）候选赢**。同一 `moreDelta` 先验证 `defaultPosition - moreDelta`（`Patch.java:180-185`），命中即 `return`，根本不会再查前向 `defaultPosition + moreDelta`（`Patch.java:186-191`）。新增测试 `fuzzySearchPrefersTheBackwardCandidateAtEqualDistance` 把这条钉死：把这两段对调，补丁会打到后一个候选上。
- **匹配不上怎么判冲突**：所有 fuzz 层、所有半径都没命中，`findPositionFuzzy` 返回 -1（`Patch.java:132`），调用方走 `conflictOutput.processConflict(CONTENT_DOES_NOT_MATCH_TARGET, ...)`（`Patch.java:115-116`），默认即抛 `PatchFailedException`（`Patch.java:198-201`）。
- fuzz 下内容比较忽略 chunk 两端各 `fuzz` 个元素：`Chunk.verifyChunk(target, fuzz, position)` 中 `startIndex=fuzz`、`lastIndex=size-fuzz`（`Chunk.java:112-113`，比较循环 `:119-123`）；fuzzy 落地同样只删/插非 fuzz 部分，`ChangeDelta.applyFuzzyToAt`（`ChangeDelta.java:69-80`）。
- 一个容易踩的坑：只有 `ChangeDelta`（`ChangeDelta.java:69-80`）和 `EqualDelta`（`EqualDelta.java:40-42`）实现了 fuzzy 落地；`InsertDelta`/`DeleteDelta` 不覆盖基类方法，沿用 `AbstractDelta.applyFuzzyToAt`（`AbstractDelta.java:82-85`），一落就抛 `UnsupportedOperationException`。见 R3。

### I4. unified diff：hunk 行号换算、上下文分组、无换行标记

- **1-based → 0-based**：
  - 老读取器 `UnifiedDiffUtils.parseUnifiedDiff` 在 `UnifiedDiffUtils.java:69-70` 把 hunk 头解析成 1-based 的 `old_ln/new_ln`（`@@ -a,b +c,d @@` 的正则在 `:37-38`，0 特判回 1 在 `:72-77`），换算发生在构造 chunk 时的 `old_ln - 1` / `new_ln - 1`：`UnifiedDiffUtils.java:127-128`；`-`/`+` 行的 changePosition 同样在 `:115`、`:122` 做 `-1`。
  - 新读取器 `UnifiedDiffReader.processChunk` 先在 `UnifiedDiffReader.java:365-368` 读入 1-based 的 `old_ln/new_ln`（0 特判 `:369-374`），换算发生在 `finalizeChunk` 构造四个具体 delta 时的 `old_ln - 1` / `new_ln - 1`：`UnifiedDiffReader.java:314-315, 318-319, 322-323, 326-327`；增删行索引在 `:353`、`:360`。
  - 写出方向（0-based → 1-based）在两个 writer 的 `processDeltas`：`origStart = position + 1 - contextSize`（老 `UnifiedDiffUtils.java:225`、新 `UnifiedDiffWriter.java:141`），`revStart` 同理（老 `:231`、新 `:147`），header 拼接在老 `:283-293`、新 `:200`。
- **上下文行数如何参与 hunk 分组**：分组条件在老 `UnifiedDiffUtils.java:179-180` 与新 `UnifiedDiffWriter.java:94-95`：当 `curDelta.source.position + curDelta.source.size + contextSize >= nextDelta.source.position - contextSize` 时并入同一 hunk，否则封块新开。也就是说两个 delta 之间的原文间隔 `<= 2*contextSize` 就合并（边界相等也合并）。组内前导/后置上下文分别从 `position - contextSize`（钳到 0，老 `:237-240`、新 `:153-156`）和末尾起取 `contextSize` 行（老 `:273-279`、新 `:190-196`），且计数累加进 header 的 orig/rev 总数。
- **`\ No newline at end of file` 的两套处理**：
  - 老实现：完全不认识。读取时它既不以空格/`+`/`-` 开头又不匹配 hunk 正则，`UnifiedDiffUtils.java:79-87` 的分支对非空非标签行直接忽略（不落 `rawChunk`）；写出侧 `getDeltaText`（`UnifiedDiffUtils.java:304-313`）也永不输出该标记。
  - 新实现读取：`UnifiedDiffReader.checkForNoNewLineAtTheEndOfTheFile`（`UnifiedDiffReader.java:214-220`）识别该精确字符串，置 `actualFile.setNoNewLineAtTheEndOfTheFile(true)`（`:216`，标志定义 `unifieddiff/UnifiedDiffFile.java:45, 204-210`），然后**吞掉这一行**（读下一行，`:217`），不把它计入任何 chunk；调用点在 hunk 数据循环前后两处 `:177`、`:193`。
  - 新实现写出：`UnifiedDiffWriter` 全程没有读取该标志，`getDeltaText`（`UnifiedDiffWriter.java:212-219`）同样永不输出该标记。即"读得到、写不回、apply 也不消费"，这正是已存在但被 `@Disabled("for next release")` 的往返测试 `UnifiedDiffRoundTripNewLineTest.java:25-45`（issue 135）所记录的洞，见 R4 之外的第三节 R3 说明。

### I5. `DiffRowGenerator` 行内切词规则与标签成对闭合

- 切词：
  - 默认按字符：`SPLITTER_BY_CHARACTER`（`DiffRowGenerator.java:65-71`），也是 Builder 默认值（`:535`）。
  - 按词：`SPLITTER_BY_WORD`（`:78-79`）使用正则 `SPLIT_BY_WORD_PATTERN = \s+|[,.\[\](){}/\\*+\-#<>;:&']+`（`:73`），通过 `splitStringPreserveDelimiter`（`:101-118`）切分——匹配段（空白/标点串）本身也作为独立 token 保留（`:110`），非匹配的词段在 `:108`/`:114` 保留。Builder 开关 `inlineDiffByWord`（`:710-713`），也可用 `inlineDiffBySplitter` 自定义（`:723-726`）。
  - 行内 diff 前先把整个 delta 的多行用 `\n` 拼成单串再切（`DiffRowGenerator.java:397-401`），结束后再按 `\n` 拆回行（`:486-487`）。
- 行内 delta 计算：`DiffUtils.diff(origList, revList, equalizer)`（`:403-404`），可经 `inlineDeltaMerger` 合并（`:405-406`，默认不合并 `:83-84`，可按空白相等段合并 `:89-91`）。
- **成对闭合的关键在顺序**：先 `Collections.reverse(inlineDeltas)`（`DiffRowGenerator.java:408`），再从后往前把标签插进 token list（循环 `:409-476`）。因为插标签会改变后面 token 的下标，逆序处理保证"还没处理的更早 delta"的 position 不被污染。
- 单个区间的闭合由 `wrapInTag`（`DiffRowGenerator.java:128-179`）保证：它总是成对地 `sequence.add(endPos, 闭合标签)`（`:155`，`tagGenerator.apply(tag,false)`）与 `sequence.add(endPos, 开始标签)`（`:176`，`apply(tag,true)`），每次处理一对后 `endPos--`（`:159, 177`）继续向内；遇到区间全是 `\n` 或空区间就 `break`（`:151-153`），不会产生孤立标签。多行区间会在换行处拆成多对标签（`:141-149, 162-174`），从而标签不跨 `\n`。
- 逆序这一步若被对调成"先 wrap 再 reverse"或直接删掉，后续 delta 的 position 会因为已插入的标签整体右移而错位，标签会嵌套/包错词。实测对 `the quick brown fox → the slow red fox`（按词），正确输出是
  `the [OLD]quick[/OLD] [OLD]brown[/OLD] fox`；正向插入会得到
  `the [OLD]quick[OLD][/OLD][/OLD] brown fox` 这种嵌套错乱（开始标签进了区间内、第二个词完全没被包）。对应第四节 Q2。

## 三、三个真正有风险的地方（三个不同文件）

### R1. `Chunk.verifyChunk` 边界判定过严，合法尾位置会先误判、越界时直接抛异常

- 位置：`java-diff-utils/src/main/java/com/github/difflib/patch/Chunk.java:116`
  ```java
  if (position + fuzz > target.size() || last - fuzz > target.size()) {
      return VerifyChunk.POSITION_OUT_OF_TARGET;
  }
  ```
  其中 `last = position + size() - 1`（`Chunk.java:114`），随后的比较循环会读 `target.get(position + i)`（`Chunk.java:120`）。
- 触发形状：
  1. **会崩**的形状：非空 chunk 的 `position == target.size()`（例如手工构造/反序列化得到的、声称锚在列表末尾之后的 delta）。此时 `position + 0 > size` 为假（相等），`last - 0 = size` 也只是"等于"size，两个条件都放过去；循环第一次就 `target.get(size)` 抛 `IndexOutOfBoundsException`。`verifyChunk`/`applyTo` 的契约是返回 `VerifyChunk`（或 `PatchFailedException`），实际却是未受检的 `IndexOutOfBoundsException`，`AbstractDelta.verifyAndApplyTo`（`AbstractDelta.java:62-66`）和冲突处理器（`Patch.java:76-78`）都接不住它。实测：对 3 元素 list 调 `new Chunk<>(3, ["Z"]).verifyChunk(target)` 直接抛 `Index 3 out of bounds for length 3`。
  2. fuzzy 形状：当 chunk 两端各去掉 `fuzz` 后剩余窗口末端恰好等于 `target.size()` 时，`last - fuzz > size` 的严格 `>` 也会把这种"末端刚好贴边"的合法情况误判成 `POSITION_OUT_OF_TARGET`（真正的上界应该是 `>=`，或等价地比较"最后一个要读的下标 + 1"）。
- 复现步骤：
  1. `new Patch<String>()`，`addDelta(new ChangeDelta<>(new Chunk<>(3, Arrays.asList("Z")), new Chunk<>(3, Arrays.asList("Q"))))`；
  2. `patch.applyTo(Arrays.asList("a","b","c"))`；
  3. 预期得到 `PatchFailedException`（或 `POSITION_OUT_OF_TARGET` 走冲突输出），实际抛 `IndexOutOfBoundsException`。
- 现有测试为什么没盖住：`ChunkTest.verifyChunk`（`src/test/java/com/github/difflib/patch/ChunkTest.java:10-32`）只覆盖了正常命中、中部内容不匹配和 fuzz=1 的命中/不匹配，没有任何用例把 position 推到 `== size()`，也没有断言返回 `POSITION_OUT_OF_TARGET`（该枚举值在全仓库测试里都没出现）。fuzzy 测试（`WithMyersDiffWithLinearSpacePatchTest.java:92-151`）的落点都被 `Patch.findPositionWithFuzzAndMoreDelta` 的 after 边界（`Patch.java:172-178`）提前过滤，走不到 `verifyChunk` 的这条判定。

### R2. equalizer 传 `null` 硬编码 `new MyersDiff<>()`，绕过全局默认算法工厂

- 位置：`DiffUtils.java:119`（对比正确分支 `DiffUtils.java:117`）。
- 触发形状：任何先调用 `DiffUtils.withDefaultDiffAlgorithmFactory(f)`（`DiffUtils.java:47-49`）替换默认算法（线性空间 Myers、jgit HistogramDiff、测试替身等），随后又调用 7 参重载 `DiffUtils.diff(source, target, null)` 的代码。`DiffRowGenerator` 是最现实的放大器：它的行级/行内 diff 都走 `DiffUtils.diff(..., equalizer)`（`DiffRowGenerator.java:237, 404`），而构造器在 Builder 未显式设置 equalizer 时总会选出一个非空谓词（`DiffRowGenerator.java:209-213`），所以内部不踩；但任何外部调用方按 Javadoc"传 null 用默认"的说明（`DiffUtils.java:108-110`）就会踩。
- 复现步骤：
  1. `DiffUtils.withDefaultDiffAlgorithmFactory(MyersDiffWithLinearSpace.factory())`；
  2. 对 `[x,a,b,c,y]` vs `[x,a,BB,c,y]`（单行替换）分别调 `diff(a,b,null)` 与 `diff(a,b,String::equals)`；
  3. 前者得到 1 个 `CHANGE`（标准 Myers），后者得到 `INSERT+DELETE` 两个 delta（线性空间 Myers，见 1.2/1.4）——同进程、同输入，两条"文档等价"的路产出不同 patch，且全局开关对 null 路静默失效。
- 现有测试为什么没盖住：没有任何测试先 `withDefaultDiffAlgorithmFactory` 再走 `:114` 这个重载；工厂替换只在线性空间/jgit 模块里以显式算法实例的方式被测（如 `WithMyersDiffWithLinearSpacePatchTest.java:159, 182`），`:119` 这行硬编码从未被断言过。另外该状态是静态可变的，这类用例还会污染同 JVM 内后续测试，作者通常会回避。

### R3. `applyFuzzy` 对纯 INSERT/DELETE 必然抛 `UnsupportedOperationException`

- 位置：`InsertDelta.java`、`DeleteDelta.java` 未覆盖 `applyFuzzyToAt`，落到基类 `AbstractDelta.java:82-85`（默认直接抛异常）；调用点 `Patch.java:112`。
- 触发形状：用 `applyFuzzy(target, maxFuzz)` 应用一个含纯插入或纯删除 delta 的 patch——而这是除全局工厂被换成线性空间 Myers 外最常见的 delta 形状（标准 Myers 对"只加/只减行"就产 INSERT/DELETE，见 `MyersDiff.java:169-172`）。不需要真的发生漂移：即使 delta 恰好在默认位置、`fuzz=0` 精确命中（`Patch.java:138-140`），最后一步 `delta.applyFuzzyToAt(...)`（`Patch.java:112`）照样抛。
- 复现步骤：
  1. 构造 `InsertDelta(Chunk(1, []), Chunk(1, ["NEW"]))` 放进 patch；
  2. `patch.applyFuzzy(new ArrayList<>([pad,x,a,b,c]), 2)`；
  3. 实际得到 `UnsupportedOperationException: InsertDelta does not supports applying patch fuzzy`（DeleteDelta 同理）。
- 现有测试为什么没盖住：唯一的 fuzzy 应用测试 `WithMyersDiffWithLinearSpacePatchTest` 里手工构造的全是 6 行换 6 行的 `ChangeDelta`（该文件 `:97, 133-134, 144-145`），从未放入 Insert/Delete；`AbstractDelta.applyFuzzyToAt` 的默认异常路径因此零覆盖。方法 Javadoc（`AbstractDelta.java:73-80`）也没有提示"仅 CHANGE 支持 fuzzy"，调用方无法从 API 表面察觉。

### 附：另一个已被项目自己标记但未修的洞（不计入上面三选）

`UnifiedDiffReader` 能读到 `\ No newline at end of file` 标志（`UnifiedDiffReader.java:214-220`），但 `UnifiedDiffWriter`（整个文件无该字符串）和 patch apply 都不消费它，往返会丢失"文件末尾无换行"语义。已有测试 `UnifiedDiffRoundTripNewLineTest.java:25-45` 精确描述了该问题，但类上挂着 `@Disabled("for next release")`（该文件 `:24`），所以全绿并不代表行为正确。

## 四、相邻两步不能对调：两处实例

### Q1. 应用路上：必须"先 verify 后 applyTo"

- 位置：`AbstractDelta.verifyAndApplyTo`，`AbstractDelta.java:61-67`。当前顺序是 `verifyChunkToFitTarget(target)`（`:62`）→ 仅 `OK` 时 `applyTo(target)`（`:63-65`）。
- 对调后首先失效的一步：`Patch.applyToExisting` 依赖"返回非 OK 时目标尚未被修改"这一前提去做冲突处理（`Patch.java:75-78`）。顺序对调（或删掉 `if (verify == OK)` 守卫，编译照样过）后，不匹配的 delta 会先在错误位置 `remove/add`（`ChangeDelta.java:45-52` 等），随后才抛出/产生冲突标记，传入的 list 已被污染；git 风格冲突处理器（`Patch.java:206-227`）还会在已被改坏的 list 上再插一遍 `<<<<<< HEAD` 块，输出彻底乱套。
- 错出来的样子：对 `[aaa, bbb, ccc] → [aaa, BBB, ccc]` 的补丁，把目标第二行改成 `XXX` 再应用，正确行为是抛 `PatchFailedException` 且目标保持 `[aaa, XXX, ccc]`；对调后目标先变成 `[aaa, BBB, ccc]`，再带着异常返回/再叠冲突标记。新增测试 `failingVerificationMustLeaveTargetUntouched` 正是钉这一条（变异验证：删掉守卫后该测试立即红，实际污染成 `[aaa, BBB, ccc]`）。

### Q2. 渲染路上：必须"先 reverse 行内 delta，再插标签"

- 位置：`DiffRowGenerator.generateInlineDiffs`，`DiffRowGenerator.java:408` 的 `Collections.reverse(inlineDeltas)` 必须发生在插标签循环（`:409-476`）之前；每段区间又必须在 `wrapInTag` 里先插闭合标签再插开始标签（`:155` 先于 `:176`）。
- 对调后首先失效的一步：往 token list 里 `add` 标签（`DiffRowGenerator.java:155, 176`）会改变所有更大下标。正向处理第一个 delta 后，第二个 delta 的 `position/position+size`（如 `:415-416, 437-438, 469-470`）已经不再指向它原本的 token，插进去的标签会落在被前一组标签挤偏的位置；闭合标签同理，`wrapInTag` 内先开后闭也会让闭合标签包住自己的开始标签。
- 错出来的样子（实测，按词切分，`the quick brown fox → the slow red fox`）：
  - 正确：`the [OLD]quick[/OLD] [OLD]brown[/OLD] fox` / `the [NEW]slow[/NEW] [NEW]red[/NEW] fox`；
  - 对调后：`the [OLD]quick[OLD][/OLD][/OLD] brown fox` / `the [NEW]slow[NEW][/NEW][/NEW] red fox`——两组标签嵌成 `[OLD]...[OLD][/OLD][/OLD]`，第二处差异 `brown/red` 完全没有标签包裹。HTML 场景下这会生成跨词嵌套、甚至跨未闭合的非法标签片段，页面高亮整段错位，而不会有任何异常提示。

## 五、构建与测试布局的一个事实

根 `pom.xml` 的 surefire 配置无条件排除 `**/LR*.java`：`pom.xml:180-187`（`<exclude>**/LR*.java</exclude>` 在 `:184`）。唯一命中的类是 `java-diff-utils-jgit/src/test/java/com/github/difflib/algorithm/jgit/LRHistogramDiffTest.java`（大数据集 issue26 的性能/正确性用例，`:41-69`），因此它**从未被 surefire 执行过**，全量 `mvn test` 的"绿"不含这一类。本次按要求未修改该排除规则，也未改动该模块任何文件。
