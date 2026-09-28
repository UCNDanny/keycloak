import {
  FormSubmitButton,
  TextControl,
  useAlerts,
} from "@keycloak/keycloak-ui-shared";
import {
  Button,
  ButtonVariant,
  Form,
  Modal,
  ModalVariant,
} from "@patternfly/react-core";
import { FormProvider, useForm } from "react-hook-form";
import { useTranslation } from "react-i18next";
import { useAdminClient } from "../admin-client";

type CreateMemberForm = {
  username: string;
  email: string;
  firstName: string;
  lastName: string;
};

type CreateMemberModalProps = {
  orgId: string;
  onClose: () => void;
};

export const CreateMemberModal = ({
  orgId,
  onClose,
}: CreateMemberModalProps) => {
  const { adminClient } = useAdminClient();
  const { addAlert, addError } = useAlerts();

  const { t } = useTranslation();
  const form = useForm<CreateMemberForm>();
  const { handleSubmit, formState } = form;

  const submitForm = async (data: CreateMemberForm) => {
    try {
      await adminClient.organizations.createMember({ orgId, ...data });
      addAlert(t("createMemberSuccess"));
      onClose();
    } catch (error) {
      addError("createMemberError", error);
    }
  };

  return (
    <Modal
      variant={ModalVariant.small}
      title={t("createMember")}
      description={t("createMemberHelp")}
      isOpen
      onClose={onClose}
      actions={[
        <FormSubmitButton
          formState={formState}
          data-testid="save"
          key="confirm"
          form="create-member-form"
          allowInvalid
          allowNonDirty
        >
          {t("create")}
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
        <Form id="create-member-form" onSubmit={handleSubmit(submitForm)}>
          <TextControl
            name="username"
            label={t("username")}
            rules={{ required: t("required") }}
            autoFocus
          />
          <TextControl name="email" label={t("email")} type="email" />
          <TextControl name="firstName" label={t("firstName")} />
          <TextControl name="lastName" label={t("lastName")} />
        </Form>
      </FormProvider>
    </Modal>
  );
};
