export interface EnumValueSchema {
  name: string
  number: number
}

export interface FieldSchema {
  name: string
  jsonName: string
  type: string
  label: 'optional' | 'repeated' | 'map'
  typeName?: string
  fields: FieldSchema[]
  enumValues: EnumValueSchema[]
  oneofName?: string
  mapEntry: boolean
  wellKnownType?: string
}

export interface MessageSchema {
  fullName: string
  fields: FieldSchema[]
}

export type MethodType = 'UNARY' | 'SERVER_STREAMING' | 'CLIENT_STREAMING' | 'BIDI_STREAMING'

export interface MethodSchema {
  name: string
  fullServiceName: string
  methodType: MethodType
  inputSchema: MessageSchema
  outputTypeName: string
  httpMethod?: string
  httpPath?: string
}

export interface ServiceSchema {
  name: string
  target: string
  methods: MethodSchema[]
  namespace: string
  configName: string
}

export interface OAuthFlowDto {
  authorizationUrl?: string
  tokenUrl?: string
  scopes?: Record<string, string>
}

export interface AuthProviderDto {
  id: string
  type: 'http' | 'oauth2' | 'apiKey' | 'openIdConnect'
  scheme?: string
  description?: string
  apiKeyIn?: string
  apiKeyName?: string
  authorizationCode?: OAuthFlowDto
  clientCredentials?: OAuthFlowDto
}

export interface GatewayUiLinks {
  swaggerEnabled: boolean
  swaggerPath: string
  graphqlEnabled: boolean
  graphqlPath: string
  grpcProxyEnabled: boolean
  grpcProxyPort: number
  authProviders: AuthProviderDto[]
  pushEnabled?: boolean
  pushPath?: string
  pushAdminPath?: string
}

export interface InvokeResponse {
  response: string | null
  grpcStatus: string
  errorMessage: string | null
  /** google.rpc.Status details the backend attached, each as proto3 JSON with its @type */
  errorDetails?: unknown[] | null
  durationMs: number
}

/** Payload of the `error` SSE event on a streaming invoke. */
export interface StreamError {
  code: string
  message: string | null
  details?: unknown[]
}

export type GroupBy = 'flat' | 'namespace' | 'target' | 'configName'
export type RequestMode = 'json' | 'form'