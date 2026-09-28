import type { Page } from "@playwright/test";
import adminClient from "../utils/AdminClient.ts";

export type ScopedUser = { username: string; password: string };

/**
 * Creates a user that only holds realm-management `query-organizations` and is
 * granted the given scopes on one organization through an FGAP v2 permission.
 */
export async function createOrganizationScopedUser({
  realm,
  orgId,
  username,
  scopes,
}: {
  realm: string;
  orgId: string;
  username: string;
  scopes: ("view" | "manage")[];
}): Promise<ScopedUser> {
  const password = "password";
  const user = await adminClient.createUser({
    realm,
    username,
    enabled: true,
    email: `${username}@example.com`,
    firstName: username,
    lastName: "Admin",
    credentials: [{ type: "password", value: password, temporary: false }],
  });
  await adminClient.addClientRoleToUser(
    user.id!,
    "realm-management",
    ["query-organizations"],
    realm,
  );
  const { id: policyId } = await adminClient.createUserPolicy({
    realm,
    name: `${username}-policy`,
    type: "user",
    username,
  });
  await adminClient.createPermission({
    realm,
    name: `${username}-permission`,
    resourceType: "Organizations",
    resources: [orgId],
    scopes,
    policies: [policyId!],
  });
  return { username, password };
}

export async function openAddMemberMenu(page: Page) {
  await page.getByTestId("add-member-toggle").click();
}

export async function fillCreateMemberModal(
  page: Page,
  values: {
    username: string;
    email?: string;
    firstName?: string;
    lastName?: string;
  },
) {
  const dialog = page.getByRole("dialog", { name: "Create member" });
  for (const [field, value] of Object.entries(values)) {
    await dialog.getByTestId(field).fill(value);
  }
  await dialog.getByTestId("save").click();
}

export function getForbiddenMessage(page: Page) {
  return page.getByText("Forbidden, permission needed: view-organizations");
}
