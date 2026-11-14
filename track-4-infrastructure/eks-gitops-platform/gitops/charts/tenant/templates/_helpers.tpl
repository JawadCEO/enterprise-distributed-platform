{{- define "tenant.labels" -}}
platform/tenant: {{ .Values.name }}
app.kubernetes.io/managed-by: argocd
{{- end -}}

{{- /* Contact details are not valid label values (e.g. email), so they are annotations. */ -}}
{{- define "tenant.annotations" -}}
platform/owner: {{ .Values.owner | quote }}
{{- end -}}
