{{/*
Labels of every object of an app; called with (dict "root" $ "name" "<app>").
*/}}
{{- define "payment-gateway.labels" -}}
{{ include "payment-gateway.selectorLabels" . }}
app.kubernetes.io/version: {{ include "payment-gateway.imageTag" .root | quote }}
app.kubernetes.io/part-of: payment-gateway
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version }}
{{- end }}

{{/*
The labels that pick an app's pods; called as payment-gateway.labels is.
*/}}
{{- define "payment-gateway.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end }}

{{- define "payment-gateway.imageTag" -}}
{{ .Values.image.tag | default .Chart.AppVersion }}
{{- end }}

{{/*
One of the five apps' images; called with (dict "root" $ "name" "<image name>").
*/}}
{{- define "payment-gateway.image" -}}
{{- with .root.Values.image.registry }}{{ . }}/{{ end }}{{ .name }}:{{ include "payment-gateway.imageTag" .root }}
{{- end }}

{{/*
The URL browsers reach a host of the Ingress at; called with (dict "root" $ "host" "<host>").
*/}}
{{- define "payment-gateway.publicUrl" -}}
{{- $scheme := ternary "https" "http" (ne .root.Values.ingress.tlsSecret "") -}}
{{- if .root.Values.ingress.port -}}
{{ $scheme }}://{{ .host }}:{{ .root.Values.ingress.port }}
{{- else -}}
{{ $scheme }}://{{ .host }}
{{- end }}
{{- end }}

{{/*
The pod security context of a pod whose image runs as the given user and group; called with (dict "uid" N "gid" N).
The namespaces of deploy/k3d enforce the restricted Pod Security Standard, which this and the container's meet.
*/}}
{{- define "payment-gateway.podSecurityContext" -}}
runAsNonRoot: true
runAsUser: {{ .uid }}
runAsGroup: {{ .gid }}
fsGroup: {{ .gid }}
seccompProfile:
  type: RuntimeDefault
{{- end }}

{{- define "payment-gateway.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop:
    - ALL
{{- end }}

{{/*
What every pod of the chart shares: no API token, since nothing here talks to the Kubernetes API, and no service
link variables, since names such as REDIS_PORT would land on the apps' own settings (REDIS_PORT=tcp://...).
*/}}
{{- define "payment-gateway.podDefaults" -}}
automountServiceAccountToken: false
enableServiceLinks: false
{{- end }}

{{/*
The probes of a Spring Boot app, on its management port: the startup probe gives the JVM up to three minutes, and
the liveness and readiness probes ask the health groups Spring Boot has on Kubernetes.
*/}}
{{- define "payment-gateway.springProbes" -}}
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management
  periodSeconds: 2
  failureThreshold: 90
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management
  periodSeconds: 10
  timeoutSeconds: 3
  failureThreshold: 3
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: management
  periodSeconds: 5
  timeoutSeconds: 3
  failureThreshold: 2
{{- end }}

{{/*
A Spring Boot app's way out: the pod first waits while it is taken off the Service and the Ingress, then Spring
finishes the requests it has (up to 30 s) and stops.
*/}}
{{- define "payment-gateway.springShutdown" -}}
lifecycle:
  preStop:
    sleep:
      seconds: 10
{{- end }}

{{/*
The environment every Spring Boot app gets: the management port, the health groups the probes ask (Spring Boot
has them when it can tell it runs on Kubernetes; with this it does not have to tell), and where traces go.
*/}}
{{- define "payment-gateway.springEnv" -}}
- name: MANAGEMENT_SERVER_PORT
  value: "8081"
{{ include "payment-gateway.probeGroupsEnv" . }}
{{- with .Values.tracing.otlpEndpoint }}
- name: MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT
  value: {{ . | quote }}
{{- end }}
{{- end }}

{{- define "payment-gateway.probeGroupsEnv" -}}
- name: MANAGEMENT_ENDPOINT_HEALTH_PROBES_ENABLED
  value: "true"
{{- end }}

{{/*
A key of the Secret as an environment variable; called with (dict "root" $ "name" "<variable>" "key" "<key>"), and
"optional" true for a key the Secret may lack.
*/}}
{{- define "payment-gateway.secretEnv" -}}
- name: {{ .name }}
  valueFrom:
    secretKeyRef:
      name: {{ .root.Values.existingSecret }}
      key: {{ .key | default .name }}
      {{- if .optional }}
      optional: true
      {{- end }}
{{- end }}

{{/*
The Redis the apps use.
*/}}
{{- define "payment-gateway.redisEnv" -}}
{{- if .Values.redis.enabled -}}
- name: REDIS_HOST
  value: redis
- name: REDIS_PORT
  value: "6379"
{{- else -}}
- name: REDIS_HOST
  value: {{ required "redis.host is needed when redis.enabled is false" .Values.redis.host | quote }}
- name: REDIS_PORT
  value: {{ .Values.redis.port | int | quote }}
{{- end }}
{{- end }}

{{/*
Where the gateway and the ledger find Kafka: the broker of the namespace, or the cluster of kafka.bootstrapServers.
*/}}
{{- define "payment-gateway.kafkaEnv" -}}
- name: KAFKA_BOOTSTRAP_SERVERS
{{- if .Values.kafka.enabled }}
  value: kafka:9092
{{- else }}
  value: {{ required "kafka.bootstrapServers is needed when kafka.enabled is false" .Values.kafka.bootstrapServers | quote }}
{{- end }}
- name: KAFKA_SECURITY_PROTOCOL
  value: {{ .Values.kafka.securityProtocol | quote }}
{{- end }}
