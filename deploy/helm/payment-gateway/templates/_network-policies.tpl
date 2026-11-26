{{/*
A NetworkPolicy peer: the pods of one app of this release; called with (dict "root" $ "name" "<app>").
*/}}
{{- define "payment-gateway.appPeer" -}}
- podSelector:
    matchLabels:
      {{- include "payment-gateway.selectorLabels" . | nindent 6 }}
{{- end }}

{{/*
A NetworkPolicy peer: the ingress controller's pods.
*/}}
{{- define "payment-gateway.ingressControllerPeer" -}}
- namespaceSelector:
    matchLabels:
      kubernetes.io/metadata.name: {{ .Values.networkPolicy.ingressController.namespace }}
  podSelector:
    matchLabels:
      {{- toYaml .Values.networkPolicy.ingressController.podLabels | nindent 6 }}
{{- end }}

{{/*
The peers that may reach the management ports: the readiness test of helm test, and Prometheus' namespace.
*/}}
{{- define "payment-gateway.managementPeers" -}}
{{ include "payment-gateway.appPeer" (dict "root" . "name" "readiness-test") }}
{{- with .Values.networkPolicy.monitoringNamespace }}
- namespaceSelector:
    matchLabels:
      kubernetes.io/metadata.name: {{ . }}
{{- end }}
{{- end }}
