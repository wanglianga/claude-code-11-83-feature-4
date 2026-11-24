#!/usr/bin/env bash
# 抽样设计加权估计 —— 题给样例端到端验证脚本
# 前置：服务已启动；启动时自动播种抽样框 F2026-S1（幂等）。用法: ./verify_sampling.sh [BASE_URL]
set -u

BASE="${1:-${BASE_URL:-http://localhost:8080}}"
AUTH="${API_AUTH:-admin:admin123}"
PY="${PY:-python3}"
FRAME="F2026-S1"
PASS=0
FAIL=0

say()  { printf '%s\n' "$*"; }
ok()   { PASS=$((PASS+1)); say "  PASS  $1"; }
bad()  { FAIL=$((FAIL+1)); say "  FAIL  $1"; }
# 字符串/整数断言：check <名称> <实际> <期望>
check() { [ "$2" = "$3" ] && ok "$1" || bad "$1 （期望=$3 实际=$2）"; }
# 数值断言：check_num <名称> <实际> <期望> <容差>
check_num() {
  "$PY" -c "
import sys
name,got,exp,tol=sys.argv[1],float(sys.argv[2]),float(sys.argv[3]),float(sys.argv[4])
ok=abs(got-exp)<=tol
print(('  PASS  ' if ok else '  FAIL  ')+name+f' 实际={got:.6f} 期望={exp:.6f}')
sys.exit(0 if ok else 1)" "$1" "$2" "$3" "$4" && PASS=$((PASS+1)) || FAIL=$((FAIL+1))
}
# JSON 提取：jval '<python 表达式，d 为 JSON>'
jval() { curl -s -u "$AUTH" "$BASE$1" | "$PY" -c "import json,sys;d=json.load(sys.stdin);print($2)"; }

say "== 0. 抽样框总览（复检不新增单位） =="
OV=$(curl -s -u "$AUTH" "$BASE/api/sampling/frames/$FRAME/overview")
check "抽样单位数=60" "$(echo "$OV" | $PY -c 'import json,sys;print(json.load(sys.stdin)["samplingUnitCount"])')" "60"
check "风险加抽单位=20" "$(echo "$OV" | $PY -c 'import json,sys;print(json.load(sys.stdin)["oversampleCount"])')" "20"
check "非响应单位=2" "$(echo "$OV" | $PY -c 'import json,sys;print(json.load(sys.stdin)["nonResponseCount"])')" "2"
check "复检报告=2 且去重单位仍=60" "$(echo "$OV" | $PY -c 'import json,sys;d=json.load(sys.stdin);print(d["recheckReportCount"],d["distinctSamplingUnits"])')" "2 60"

say "== 1. 未加权 vs 设计加权（风险加抽被入样概率还原） =="
EST=$(curl -s -u "$AUTH" "$BASE/api/estimation/frames/$FRAME/estimates")
check_num "未加权率=9/58（高估）" "$(echo "$EST"|$PY -c 'import json,sys;print(json.load(sys.stdin)["naive"]["pointEstimate"])')" "$(python3 -c 'print(9/58)')" 1e-9
check_num "设计加权 HT 率=0.125" "$(echo "$EST"|$PY -c 'import json,sys;print(json.load(sys.stdin)["designWeighted"]["diagnostics"]["htEstimate"])')" "0.125" 1e-9
check_num "设计加权 Hařek 率=50/380" "$(echo "$EST"|$PY -c 'import json,sys;print(json.load(sys.stdin)["designWeighted"]["pointEstimate"])')" "$(python3 -c 'print(50/380)')" 1e-9
check_num "响应者权重分母=380" "$(echo "$EST"|$PY -c 'import json,sys;print(json.load(sys.stdin)["designWeighted"]["weightDenominator"])')" "380" 1e-6
# 未加权必须高于设计加权，证明朴素估计高估
"$PY" -c "
import json,sys
d=json.loads('''$EST''')
sys.exit(0 if d['naive']['pointEstimate']>d['designWeighted']['pointEstimate'] else 1)" && ok "未加权率 > 设计加权率（朴素高估成立）" || bad "未加权率应高于设计加权率"

say "== 2. 权重分母与抽样框总量对平（非响应形成缺口） =="
check "对平守卫 balanced=false（有非响应）" "$(echo "$EST"|$PY -c 'import json,sys;print(json.load(sys.stdin)["reconciliationGuard"]["balanced"])')" "False"
check_num "框总量 N=400" "$(echo "$EST"|$PY -c 'import json,sys;print(json.load(sys.stdin)["targetPopulation"])')" "400" 1e-9
check_num "非响应权重缺口=20" "$(echo "$EST"|$PY -c 'import json,sys;g=json.load(sys.stdin)["reconciliationGuard"];print(g["frameTotal"]-g["respondentDesignWeightSum"])')" "20" 1e-6

say "== 3. 校准权重合计=辅助总量 400 且收敛 =="
check "校准收敛" "$(echo "$EST"|$PY -c 'import json,sys;print(json.load(sys.stdin)["calibrated"]["diagnostics"]["calibrationConvergence"])')" "CONVERGED"
check_num "校准权重和=400" "$(echo "$EST"|$PY -c 'import json,sys;print(json.load(sys.stdin)["calibrated"]["weightDenominator"])')" "400" 1e-6

say "== 4. 重抽样与设计诊断 =="
DIAG=$(curl -s -u "$AUTH" "$BASE/api/estimation/frames/$FRAME/diagnostics?replications=2000&seed=42")
check_num "最大设计权重=10（郊县 pi=0.1）" "$(echo "$DIAG"|$PY -c 'import json,sys;print(json.load(sys.stdin)["weightDiagnostics"]["maxWeight"])')" "10" 1e-9
check "风险加抽全部在框内且 N 不变" "$(echo "$DIAG"|$PY -c 'import json,sys;o=json.load(sys.stdin)["oversampleCheck"];print(o["allOversamplesWithinFrame"],o["targetPopulationUnchanged"])')" "True 400"
check_num "非响应最坏包络宽度=0.05" "$(echo "$DIAG"|$PY -c 'import json,sys;print(json.load(sys.stdin)["nonResponseImpact"]["worstCaseEnvelope"]["envelopeWidth"])')" "0.05" 1e-9
check "样例无覆盖缺口" "$(echo "$DIAG"|$PY -c 'import json,sys;print(len(json.load(sys.stdin)["coverageGaps"]["zeroSampleStrata"]))')" "0"
BOOT_SE=$(echo "$DIAG"|$PY -c 'import json,sys;print(json.load(sys.stdin)["stratifiedBootstrap"]["bootstrapStandardError"])')
ANA_SE=$(echo "$EST"|$PY -c 'import json,sys;print(json.load(sys.stdin)["designWeighted"]["standardError"])')
"$PY" -c "import sys;sys.exit(0 if abs(float('$BOOT_SE')-float('$ANA_SE'))<=float('$ANA_SE')*0.25 else 1)" \
  && ok "Bootstrap SE($BOOT_SE) 与解析 SE($ANA_SE) 接近" || bad "Bootstrap 与解析标准误差异过大"

say ""
say "结果：PASS=$PASS FAIL=$FAIL"
[ "$FAIL" -eq 0 ]
