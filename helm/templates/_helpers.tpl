{{/*
Expand the name of the chart.
*/}}
{{- define "shiguchi.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "shiguchi.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Create chart label.
*/}}
{{- define "shiguchi.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels.
*/}}
{{- define "shiguchi.labels" -}}
helm.sh/chart: {{ include "shiguchi.chart" . }}
{{ include "shiguchi.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels.
*/}}
{{- define "shiguchi.selectorLabels" -}}
app.kubernetes.io/name: {{ include "shiguchi.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
ServiceAccount name.
*/}}
{{- define "shiguchi.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "shiguchi.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
JVM flags for JAVA_TOOL_OPTIONS, built from .Values.jvm. Empty values drop their flag, so
the JVM falls back to its own ergonomics for that setting.
*/}}
{{- define "shiguchi.javaToolOptions" -}}
{{- $jvm := .Values.jvm | default dict -}}
{{- $opts := list -}}
{{- with $jvm.gc }}{{ $opts = append $opts (printf "-XX:+Use%sGC" .) }}{{ end -}}
{{- with $jvm.maxRamPercentage }}{{ $opts = append $opts (printf "-XX:MaxRAMPercentage=%v" .) }}{{ end -}}
{{- with $jvm.activeProcessorCount }}{{ $opts = append $opts (printf "-XX:ActiveProcessorCount=%v" .) }}{{ end -}}
{{- with $jvm.options }}{{ $opts = append $opts . }}{{ end -}}
{{- with $jvm.extraOptions }}{{ $opts = append $opts . }}{{ end -}}
{{- join " " $opts -}}
{{- end }}
