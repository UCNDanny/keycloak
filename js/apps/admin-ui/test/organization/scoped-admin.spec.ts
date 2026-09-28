import { expect, test } from "@playwright/test";
import { v4 as uuid } from "uuid";
import { toEditOrganization } from "../../src/organizations/routes/EditOrganization.tsx";
import adminClient from "../utils/AdminClient.ts";
import { clickSaveButton } from "../utils/form.ts";
import { login } from "../utils/login.ts";
import { assertNotificationMessage } from "../utils/masthead.ts";
import { goToOrganizations } from "../utils/sidebar.ts";
import { assertRowExists, clickTableRowItem } from "../utils/table.ts";
import { goToMembersTab } from "./members.ts";
import {
  createOrganizationScopedUser,
  fillCreateMemberModal,
  getForbiddenMessage,
  openAddMemberMenu,
  type ScopedUser,
} from "./scoped-admin.ts";

test.describe.serial("Organization scoped admin", () => {
  const realm = `org-scoped-admin-${uuid()}`;
  const ownOrgName = `own-org-${uuid()}`;
  const otherOrgName = `other-org-${uuid()}`;
  const existingMember = `existing-member-${uuid()}`;
  let ownOrgId: string;
  let otherOrgId: string;
  let orgAdmin: ScopedUser;
  let orgViewer: ScopedUser;

  test.beforeAll(async () => {
    await adminClient.createRealm(realm, {
      enabled: true,
      organizationsEnabled: true,
      adminPermissionsEnabled: true,
    });
    for (const name of [ownOrgName, otherOrgName]) {
      await adminClient.createOrganization({
        realm,
        name,
        domains: [{ name: `${name}.org`, verified: false }],
      });
    }
    ownOrgId = (await adminClient.findOrganization(ownOrgName, realm)).id!;
    otherOrgId = (await adminClient.findOrganization(otherOrgName, realm)).id!;

    const member = await adminClient.createUser({
      realm,
      username: existingMember,
      enabled: true,
    });
    await adminClient.addOrgMember(ownOrgName, member.id!, realm);

    orgAdmin = await createOrganizationScopedUser({
      realm,
      orgId: ownOrgId,
      username: `org-admin-${uuid()}`,
      scopes: ["view", "manage"],
    });
    orgViewer = await createOrganizationScopedUser({
      realm,
      orgId: ownOrgId,
      username: `org-viewer-${uuid()}`,
      scopes: ["view"],
    });
  });

  test.afterAll(() => adminClient.deleteRealm(realm));

  test.describe.serial("with view and manage", () => {
    test.beforeEach(async ({ page }) => {
      await login(page, { realm, ...orgAdmin });
      await goToOrganizations(page);
    });

    test("sees only own organization and cannot create one", async ({
      page,
    }) => {
      await assertRowExists(page, ownOrgName);
      await assertRowExists(page, otherOrgName, false);
      await expect(page.getByTestId("addOrganization")).toBeHidden();
    });

    test("edits the organization description", async ({ page }) => {
      await clickTableRowItem(page, ownOrgName);
      await expect(page.getByTestId("name")).toHaveValue(ownOrgName);

      const description = `description ${uuid()}`;
      await page.getByTestId("description").fill(description);
      await clickSaveButton(page);
      await assertNotificationMessage(page, "Organization successfully saved.");

      const org = await adminClient.findOrganization(ownOrgName, realm);
      expect(org.description).toBe(description);
    });

    test("sees domains read-only", async ({ page }) => {
      await clickTableRowItem(page, ownOrgName);
      await page.getByTestId("domainsTab").click();
      await expect(page.getByText(`${ownOrgName}.org`)).toBeVisible();
      await expect(
        page.getByRole("button", { name: "Add domain" }),
      ).toBeHidden();
    });

    test("creates a member", async ({ page }) => {
      await clickTableRowItem(page, ownOrgName);
      await goToMembersTab(page);
      await assertRowExists(page, existingMember);

      await openAddMemberMenu(page);
      await expect(
        page.getByRole("menuitem", { name: "Invite member" }),
      ).toBeVisible();
      await expect(
        page.getByRole("menuitem", { name: "Add realm user" }),
      ).toBeHidden();
      await page.getByRole("menuitem", { name: "Create member" }).click();

      const username = `member-${uuid()}`;
      await fillCreateMemberModal(page, {
        username,
        email: `${username}@${ownOrgName}.org`,
        firstName: "New",
        lastName: "Member",
      });
      await assertNotificationMessage(page, "Member created.");
      await assertRowExists(page, username);
    });
  });

  test("cannot open another organization by URL", async ({ page }) => {
    await login(page, {
      realm,
      ...orgAdmin,
      to: toEditOrganization({ realm, id: otherOrgId, tab: "settings" }),
    });
    await expect(getForbiddenMessage(page)).toBeVisible();
    await expect(page.getByTestId("name")).toBeHidden();
  });

  test("with view only sees a read-only organization", async ({ page }) => {
    await login(page, {
      realm,
      ...orgViewer,
      to: toEditOrganization({ realm, id: ownOrgId, tab: "settings" }),
    });
    await expect(page.getByTestId("name")).toHaveValue(ownOrgName);
    await expect(page.getByTestId("name")).toBeDisabled();
    await expect(page.getByTestId("description")).toBeDisabled();
    await expect(page.getByTestId("save")).toBeHidden();

    await goToMembersTab(page);
    await assertRowExists(page, existingMember);
    await expect(page.getByTestId("add-member-toggle")).toBeHidden();
    await expect(page.getByTestId("organization-invitations-tab")).toBeHidden();
  });
});
