/**
 * AWS adapter — wires the ports once per Lambda container.
 *
 * Clients use the Lambda's default region/credentials; nothing here needs a
 * VPC, Secrets Manager or any always-on resource.
 */
import { ApiGatewayManagementApiClient } from "@aws-sdk/client-apigatewaymanagementapi";
import { DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { S3Client } from "@aws-sdk/client-s3";
import { DynamoDBDocumentClient } from "@aws-sdk/lib-dynamodb";
import type { Ports } from "../../core/ports.js";
import { createApiGwRealtime } from "./apigw-ws.js";
import { getContainerCache } from "./cache.js";
import { createDynamoDatabase } from "./dynamo.js";
import { readConfig, type AwsConfig } from "./env.js";
import { createIdGen } from "./ids.js";
import { createS3BlobStore } from "./s3.js";

export interface AwsPorts extends Ports {
  config: AwsConfig;
}

let cached: AwsPorts | undefined;

export function buildPorts(): AwsPorts {
  if (cached) return cached;
  const config = readConfig();

  const dynamo = DynamoDBDocumentClient.from(new DynamoDBClient({}), {
    marshallOptions: { removeUndefinedValues: true },
  });
  // WHEN_REQUIRED: newer SDKs otherwise embed a CRC32 of an *empty* body in
  // presigned PUT URLs (x-amz-checksum-crc32=AAAAAA==), which makes S3 reject
  // every real upload with BadDigest. The phone verifies sha256 itself.
  const s3 = new S3Client({
    requestChecksumCalculation: "WHEN_REQUIRED",
    responseChecksumValidation: "WHEN_REQUIRED",
  });
  const wsApi = new ApiGatewayManagementApiClient({ endpoint: config.wsManagementEndpoint });

  cached = {
    db: createDynamoDatabase(dynamo, config.tableName),
    blobs: createS3BlobStore(s3, config.bucketName),
    realtime: createApiGwRealtime(config.wsManagementEndpoint, wsApi),
    cache: getContainerCache(),
    clock: { now: () => Date.now() },
    ids: createIdGen(),
    config,
  };
  return cached;
}
