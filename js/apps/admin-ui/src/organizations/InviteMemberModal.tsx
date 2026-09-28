import {
  FormSubmitButton,
  TextControl,
  useAlerts,
} from "@keycloak/keycloak-ui-shared";
import {
  Button,
  ButtonVariant,
  Flex,
  FlexItem,
  Form,
  FormGroup,
  Modal,
  ModalVariant,
  Radio,
} from "@patternfly/react-core";
import { useState } from "react";
import { FormProvider, useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { useAdminClient } from "../admin-client";

type InviteBy = "email" | "username";

type InviteMemberModalProps = {
  orgId: string;
  onClose: () => void;
};

export const InviteMemberModal = ({
  orgId,
  onClose,
}: InviteMemberModalProps) => {
  const { adminClient } = useAdminClient();
  const { addAlert, addError } = useAlerts();

  const { t } = useTranslation();
  const [inviteBy, setInviteBy] = useState<InviteBy>("email");
  const form = useForm<Record<string, string>>({ shouldUnregister: true });
  const { handleSubmit, formState } = form;

  const submitForm = async (data: Record<string, string>) => {
    try {
      const formData = new FormData();
      for (const key in data) {
        formData.append(key, data[key]);
      }
      if (inviteBy === "username") {
        await adminClient.organizations.inviteExistingUser({ orgId }, formData);
      } else {
        await adminClient.organizations.invite({ orgId }, formData);
      }
      addAlert(
        t(inviteBy === "username" ? "inviteByUsernameSent" : "inviteSent"),
      );
      onClose();
    } catch (error) {
      addError("inviteSentError", error);
    }
  };

  return (
    <Modal
      variant={ModalVariant.small}
      title={t("inviteNewUser")}
      isOpen
      onClose={onClose}
      actions={[
        <FormSubmitButton
          formState={formState}
          data-testid="save"
          key="confirm"
          form="form"
          allowInvalid
          allowNonDirty
        >
          {t("send")}
        </FormSubmitButton>,
        <Button
          id="modal-cancel"
          data-testid="cancel"
          key="cancel"
          variant={ButtonVariant.link}
          onClick={onClose}
        >
          {t("cancel")}
        </Button>,
      ]}
    >
      <FormProvider {...form}>
        <Form id="form" onSubmit={handleSubmit(submitForm)}>
          <FormGroup label={t("inviteBy")} fieldId="inviteBy" role="radiogroup">
            <Flex>
              <FlexItem>
                <Radio
                  id="inviteBy-email"
                  data-testid="inviteBy-email"
                  name="inviteBy"
                  label={t("email")}
                  isChecked={inviteBy === "email"}
                  onChange={() => setInviteBy("email")}
                />
              </FlexItem>
              <FlexItem>
                <Radio
                  id="inviteBy-username"
                  data-testid="inviteBy-username"
                  name="inviteBy"
                  label={t("username")}
                  isChecked={inviteBy === "username"}
                  onChange={() => setInviteBy("username")}
                />
              </FlexItem>
            </Flex>
          </FormGroup>
          {inviteBy === "email" ? (
            <>
              <TextControl
                name="email"
                label={t("email")}
                rules={{ required: t("required") }}
                autoFocus
              />
              <TextControl name="firstName" label={t("firstName")} />
              <TextControl name="lastName" label={t("lastName")} />
            </>
          ) : (
            <TextControl
              name="username"
              label={t("username")}
              helperText={t("inviteByUsernameHelp")}
              rules={{ required: t("required") }}
              autoFocus
            />
          )}
        </Form>
      </FormProvider>
    </Modal>
  );
};
