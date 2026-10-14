{{- define "cluster.fullname" -}}{{ .Release.Name }}{{- end -}}
{{- define "cluster.storeUrl" -}}
{{- $url := ternary (printf "jdbc:postgresql://%s-postgres:5432/jenkins" (include "cluster.fullname" .)) .Values.postgres.url .Values.postgres.inChart -}}
{{- if .Values.postgres.urlParameters -}}{{ $url }}{{ ternary "&" "?" (contains "?" $url) }}{{ .Values.postgres.urlParameters }}{{- else -}}{{ $url }}{{- end -}}
{{- end -}}
{{- define "cluster.blobConnection" -}}
{{- if .Values.blob.inChart -}}DefaultEndpointsProtocol=http;AccountName=devstoreaccount1;AccountKey=Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==;BlobEndpoint=http://{{ include "cluster.fullname" . }}-azurite:10000/devstoreaccount1;{{- else -}}{{ .Values.blob.connectionString }}{{- end -}}
{{- end -}}
