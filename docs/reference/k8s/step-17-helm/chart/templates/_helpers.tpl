{{/*
_helpers.tpl : 여러 템플릿에서 재사용하는 "named template" 모음.
{{ include "webapp.fullname" . }} 처럼 include 로 불러 쓴다.
*/}}

{{/* 릴리스마다 고유한 이름: <릴리스명>-<차트명> */}}
{{- define "webapp.fullname" -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* 모든 리소스에 공통으로 붙일 레이블 */}}
{{- define "webapp.labels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{/* Deployment 셀렉터와 Pod 레이블에 쓰는 최소 셀렉터(불변이어야 함) */}}
{{- define "webapp.selectorLabels" -}}
app.kubernetes.io/name: {{ .Chart.Name }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
