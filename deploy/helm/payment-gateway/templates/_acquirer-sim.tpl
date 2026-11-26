{{/*
acquirer-sim, as itself or playing monobank: the same image twice, told apart by name. Called with
(dict "root" $ "name" "acquirer-sim" or "monobank-sim" "host" <its Ingress host> "values" <its values>).
*/}}
{{- define "payment-gateway.acquirerSim" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .name }}
  labels:
    {{- include "payment-gateway.labels" (dict "root" .root "name" .name) | nindent 4 }}
spec:
  replicas: 1
  # Its charges, challenges and webhook signing key are in memory, so a new pod replaces the old one rather than
  # running beside it.
  strategy:
    type: Recreate
  selector:
    matchLabels:
      {{- include "payment-gateway.selectorLabels" (dict "root" .root "name" .name) | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "payment-gateway.labels" (dict "root" .root "name" .name) | nindent 8 }}
    spec:
      {{- include "payment-gateway.podDefaults" .root | nindent 6 }}
      securityContext:
        {{- include "payment-gateway.podSecurityContext" (dict "uid" 10001 "gid" 10001) | nindent 8 }}
      terminationGracePeriodSeconds: 45
      containers:
        - name: {{ .name }}
          image: {{ include "payment-gateway.image" (dict "root" .root "name" "acquirer-sim") }}
          imagePullPolicy: {{ .root.Values.image.pullPolicy }}
          ports:
            - name: http
              containerPort: 8091
            - name: management
              containerPort: 8081
          env:
            {{- include "payment-gateway.springEnv" .root | nindent 12 }}
            # Its own name in traces and logs.
            - name: SPRING_APPLICATION_NAME
              value: {{ .name }}
            {{- include "payment-gateway.secretEnv" (dict "root" .root "name" "ACQUIRER_SIM_API_KEY") | nindent 12 }}
            # The challenge pages are opened in browsers, so their links use the Ingress host.
            - name: SIM_PUBLIC_URL
              value: {{ include "payment-gateway.publicUrl" (dict "root" .root "host" .host) | quote }}
          {{- include "payment-gateway.springProbes" .root | nindent 10 }}
          {{- include "payment-gateway.springShutdown" .root | nindent 10 }}
          resources:
            {{- toYaml .values.resources | nindent 12 }}
          securityContext:
            {{- include "payment-gateway.containerSecurityContext" .root | nindent 12 }}
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ .name }}
  labels:
    {{- include "payment-gateway.labels" (dict "root" .root "name" .name) | nindent 4 }}
spec:
  selector:
    {{- include "payment-gateway.selectorLabels" (dict "root" .root "name" .name) | nindent 4 }}
  ports:
    - name: http
      port: 8091
      targetPort: http
    - name: management
      port: 8081
      targetPort: management
{{- end }}
