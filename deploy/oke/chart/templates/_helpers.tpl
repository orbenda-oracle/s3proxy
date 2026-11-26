{{- define "pca-s3proxy.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "pca-s3proxy.fullname" -}}
{{- default (printf "%s-%s" .Release.Name (include "pca-s3proxy.name" .)) .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- define "pca-s3proxy.selector" -}}
app.kubernetes.io/name: {{ include "pca-s3proxy.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
{{- define "pca-s3proxy.labels" -}}
{{ include "pca-s3proxy.selector" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
{{- end -}}
{{- define "pca-s3proxy.image" -}}
{{- $repo := required "image.repository must name your imported gateway image" .Values.image.repository -}}
{{- if .Values.image.digest -}}
{{ printf "%s@%s" $repo .Values.image.digest }}
{{- else -}}
{{ printf "%s:%s" $repo (required "image.tag is required without image.digest" .Values.image.tag) }}
{{- end -}}
{{- end -}}
