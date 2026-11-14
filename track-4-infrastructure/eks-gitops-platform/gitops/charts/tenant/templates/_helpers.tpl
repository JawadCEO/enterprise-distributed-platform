{{- define "tenant.labels" -}}
platform/tenant: {{ .Values.name }}
platform/owner: {{ .Values.owner | quote }}
app.kubernetes.io/managed-by: argocd
{{- end -}}
