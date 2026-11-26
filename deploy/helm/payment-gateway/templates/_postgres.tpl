{{/*
A PostgreSQL of the namespace, for one app's database: a StatefulSet whose data stays in a volume claim when the
release is uninstalled, and its Service. Called with (dict "root" $ "name" "<object name>" "database" "<database>"
"username" "<user>" "passwordKey" "<key of the Secret>" "values" <its values: storage, storageClass, resources>).
*/}}
{{- define "payment-gateway.postgres" -}}
{{- $root := .root -}}
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: {{ .name }}
  labels:
    {{- include "payment-gateway.labels" (dict "root" $root "name" .name) | nindent 4 }}
spec:
  serviceName: {{ .name }}
  replicas: 1
  selector:
    matchLabels:
      {{- include "payment-gateway.selectorLabels" (dict "root" $root "name" .name) | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "payment-gateway.labels" (dict "root" $root "name" .name) | nindent 8 }}
    spec:
      {{- include "payment-gateway.podDefaults" $root | nindent 6 }}
      # 70 is the postgres user of the alpine image.
      securityContext:
        {{- include "payment-gateway.podSecurityContext" (dict "uid" 70 "gid" 70) | nindent 8 }}
      containers:
        - name: postgres
          image: {{ $root.Values.postgres.image }}
          imagePullPolicy: {{ $root.Values.image.pullPolicy }}
          env:
            - name: POSTGRES_DB
              value: {{ .database }}
            - name: POSTGRES_USER
              value: {{ .username | quote }}
            {{- include "payment-gateway.secretEnv" (dict "root" $root "name" "POSTGRES_PASSWORD" "key" .passwordKey) | nindent 12 }}
            # A folder inside the volume rather than the volume itself, whose top the postgres user may not own.
            - name: PGDATA
              value: /var/lib/postgresql/data/pgdata
          ports:
            - name: postgres
              containerPort: 5432
          startupProbe:
            exec:
              command: ["pg_isready", "-h", "127.0.0.1", "-U", {{ .username | quote }}, "-d", {{ .database | quote }}]
            periodSeconds: 2
            failureThreshold: 60
          livenessProbe:
            exec:
              command: ["pg_isready", "-h", "127.0.0.1", "-U", {{ .username | quote }}, "-d", {{ .database | quote }}]
            periodSeconds: 10
            timeoutSeconds: 5
            failureThreshold: 6
          readinessProbe:
            exec:
              command: ["pg_isready", "-h", "127.0.0.1", "-U", {{ .username | quote }}, "-d", {{ .database | quote }}]
            periodSeconds: 5
            timeoutSeconds: 5
          resources:
            {{- toYaml .values.resources | nindent 12 }}
          securityContext:
            {{- include "payment-gateway.containerSecurityContext" $root | nindent 12 }}
          volumeMounts:
            - name: data
              mountPath: /var/lib/postgresql/data
            # The socket's folder and /tmp, as the root file system is read-only.
            - name: run
              mountPath: /run/postgresql
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: run
          emptyDir: {}
        - name: tmp
          emptyDir: {}
  volumeClaimTemplates:
    - metadata:
        name: data
        labels:
          {{- include "payment-gateway.selectorLabels" (dict "root" $root "name" .name) | nindent 10 }}
      spec:
        accessModes:
          - ReadWriteOnce
        {{- with .values.storageClass }}
        storageClassName: {{ . }}
        {{- end }}
        resources:
          requests:
            storage: {{ .values.storage }}
---
apiVersion: v1
kind: Service
metadata:
  name: {{ .name }}
  labels:
    {{- include "payment-gateway.labels" (dict "root" $root "name" .name) | nindent 4 }}
spec:
  selector:
    {{- include "payment-gateway.selectorLabels" (dict "root" $root "name" .name) | nindent 4 }}
  ports:
    - name: postgres
      port: 5432
      targetPort: postgres
{{- end }}
