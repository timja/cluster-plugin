{{- define "cnj.fullname" -}}{{ .Release.Name }}{{- end -}}
{{- define "cnj.storeUrl" -}}
{{- if .Values.postgres.inChart -}}jdbc:postgresql://{{ include "cnj.fullname" . }}-postgres:5432/jenkins{{- else -}}{{ .Values.postgres.url }}{{- end -}}
{{- end -}}
{{- define "cnj.blobConnection" -}}
{{- if .Values.blob.inChart -}}DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://{{ include "cnj.fullname" . }}-azurite:10000/devstoreaccount1;{{- else -}}{{ .Values.blob.connectionString }}{{- end -}}
{{- end -}}
